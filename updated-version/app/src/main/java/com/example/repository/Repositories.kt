package com.example.repository

import com.example.core.AppError
import com.example.core.Outcome
import com.example.core.map
import com.example.core.onSuccess
import com.example.data.ActiveRidePointer
import com.example.data.CacheDao
import com.example.data.CachedLedgerEntry
import com.example.data.CachedProfile
import com.example.data.CachedRide
import com.example.data.CachedWallet
import com.example.data.PendingPayment
import com.example.data.PendingRideRequest
import com.example.data.RideRecord
import com.example.data.UserProfile
import com.example.data.WalletTransaction
import com.example.domain.FleetCatalog
import com.example.domain.LocationPoint
import com.example.services.AuthService
import com.example.services.CheckoutResult
import com.example.services.LedgerEntry
import com.example.services.LedgerType
import com.example.services.LocationService
import com.example.services.MapsService
import com.example.services.NotificationService
import com.example.services.OtpChallenge
import com.example.services.PaymentCheckout
import com.example.services.PaymentService
import com.example.services.PaymentStatus
import com.example.services.PilotService
import com.example.services.ProfilePatch
import com.example.services.Ride
import com.example.services.RideQuote
import com.example.services.RideService
import com.example.services.SafetyService
import com.example.services.SavedAddress
import com.example.services.SosResult
import com.example.services.UserAccount
import com.example.services.UserService
import com.example.services.WalletBalance
import com.example.services.WalletService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

// -------------------------------------------------------------------------------------- Session
class SessionRepository(private val auth: AuthService, private val cache: CacheDao) {
    val isSignedIn: Boolean get() = auth.isSignedIn
    val sessionExpired: Flow<Unit> = auth.sessionExpired

    suspend fun requestOtp(phone: String) = auth.requestOtp(phone)
    suspend fun verifyOtp(challenge: OtpChallenge, otp: String) = auth.verifyOtp(challenge, otp)

    suspend fun logout(): Outcome<Unit> {
        cache.clearAll()
        return auth.logout()
    }

    suspend fun deleteAccount(): Outcome<Unit> = auth.deleteAccount().onSuccess { cache.clearAll() }

    /** Clears local state after the server revoked the session. */
    suspend fun onSessionExpired() = cache.clearAll()
}

// -------------------------------------------------------------------------------------- Profile + wallet
class ProfileRepository(
    private val users: UserService,
    private val walletService: WalletService,
    private val cache: CacheDao,
) {
    /** Profile + wallet summary for the UI. Balances come only from server snapshots. */
    val profile: Flow<UserProfile> = combine(cache.profile(), cache.wallet()) { p, w ->
        UserProfile(
            name = p?.name.orEmpty(),
            email = p?.email.orEmpty(),
            phone = p?.phone.orEmpty(),
            emergencyContactName = p?.emergencyContactName.orEmpty(),
            emergencyContactPhone = p?.emergencyContactPhone.orEmpty(),
            emergencyRelationship = p?.emergencyRelationship.orEmpty(),
            walletBalance = (w?.availablePaise ?: 0L) / 100.0,
            walletAvailablePaise = w?.availablePaise ?: 0L,
            walletHeldPaise = w?.heldPaise ?: 0L,
            autoDialSos = p?.autoDialSos ?: false,
            walletLoaded = w != null,
        )
    }

    val transactions: Flow<List<WalletTransaction>> = cache.ledger().map { list -> list.map { it.toUi() } }

    suspend fun refreshProfile(): Outcome<UserAccount> = users.me().onSuccess { cacheProfile(it) }

    suspend fun refreshWallet(): Outcome<WalletBalance> {
        val w = walletService.wallet()
        if (w is Outcome.Success) {
            // Single signed-in account per install; the cache is wiped on sign-out.
            cache.upsertWallet(CachedWallet(WALLET_CACHE_KEY, w.value.balancePaise, w.value.heldPaise, w.value.availablePaise, System.currentTimeMillis()))
            walletService.transactions().onSuccess { entries -> cache.replaceLedger(entries.map { it.toCache() }) }
        }
        return w
    }

    /** Server-first partial update; the cache is overwritten only with the server's result. */
    suspend fun update(patch: ProfilePatch): Outcome<UserAccount> = users.update(patch).onSuccess { cacheProfile(it) }

    private companion object { const val WALLET_CACHE_KEY = "me" }

    private suspend fun cacheProfile(u: UserAccount) = cache.upsertProfile(
        CachedProfile(u.userId, u.phone, u.name, u.email, u.emergencyContactName, u.emergencyContactPhone, u.emergencyRelationship, u.autoDialSos, System.currentTimeMillis())
    )
}

internal fun LedgerEntry.toCache() = CachedLedgerEntry(id, type.name, amountPaise, balanceAfterPaise, heldAfterPaise, reference, paymentId, rideId, description, createdAt)

internal fun CachedLedgerEntry.toUi(): WalletTransaction {
    val t = runCatching { LedgerType.valueOf(type) }.getOrDefault(LedgerType.UNKNOWN)
    val (direction, category, label) = when (t) {
        LedgerType.TOPUP_CREDIT -> Triple("credit", "TOPUP", "Payment gateway")
        LedgerType.RIDE_CAPTURE -> Triple("debit", "RIDE_DEBIT", "Wallet")
        LedgerType.CANCELLATION_FEE -> Triple("debit", "RIDE_DEBIT", "Wallet")
        LedgerType.RIDE_REFUND -> Triple("credit", "REFUND", "Wallet")
        LedgerType.TOPUP_REFUND -> Triple("debit", "REFUND", "Payment gateway")
        LedgerType.RIDE_HOLD -> Triple("hold", "HOLD", "Held for ride")
        LedgerType.HOLD_RELEASE -> Triple("release", "HOLD", "Hold released")
        LedgerType.UNKNOWN -> Triple("debit", "OTHER", "Wallet")
    }
    return WalletTransaction(
        transactionId = id, timestamp = createdAt, type = direction, category = category,
        amount = amountPaise / 100.0, balanceAfter = (balanceAfterPaise - heldAfterPaise) / 100.0,
        gateway = label, status = "SUCCESS", referenceId = reference, description = description,
    )
}

// -------------------------------------------------------------------------------------- Payments
sealed interface TopUpResult {
    data class Credited(val wallet: WalletBalance) : TopUpResult
    data class Failed(val reason: String) : TopUpResult
    data object Cancelled : TopUpResult
    /** Outcome unknown (e.g. network died after paying). It is reconciled with the server later. */
    data object PendingConfirmation : TopUpResult
}

class PaymentRepository(
    private val payments: PaymentService,
    private val checkout: PaymentCheckout,
    private val cache: CacheDao,
    private val profile: ProfileRepository,
) {
    val providerLabel: String get() = checkout.providerLabel
    val isAvailable: Boolean get() = checkout.isAvailable
    private val mutex = Mutex()

    /**
     * Full top-up flow. Serialized with a mutex (double-tap safe) and idempotent on the server
     * via [idempotencyKey]. The wallet is credited only by the server after it verifies the
     * provider's signature or webhook.
     */
    suspend fun topUp(amountPaise: Long, idempotencyKey: String): Outcome<TopUpResult> = mutex.withLock {
        val order = when (val o = payments.createOrder(amountPaise, idempotencyKey)) {
            is Outcome.Success -> o.value
            is Outcome.Failure -> return o
        }
        if (order.status != PaymentStatus.PENDING) return Outcome.Success(settled(order.status))
        cache.insertPendingPayment(PendingPayment(order.paymentId, idempotencyKey, amountPaise, System.currentTimeMillis()))
        val result = when (val c = checkout.launch(order)) {
            is Outcome.Success -> c.value
            is Outcome.Failure -> {
                payments.verify(order, CheckoutResult.Failed(c.error.message))
                cache.deletePendingPayment(order.paymentId)
                return c
            }
        }
        return when (val v = payments.verify(order, result)) {
            is Outcome.Success -> {
                cache.deletePendingPayment(order.paymentId)
                profile.refreshWallet()
                Outcome.Success(
                    when (v.value.first) {
                        PaymentStatus.SUCCESS -> TopUpResult.Credited(v.value.second)
                        PaymentStatus.CANCELLED -> TopUpResult.Cancelled
                        PaymentStatus.PENDING -> TopUpResult.PendingConfirmation
                        else -> TopUpResult.Failed("Payment was not completed")
                    }
                )
            }
            is Outcome.Failure -> {
                if (v.error.isRetryable) Outcome.Success(TopUpResult.PendingConfirmation) // keep pending, reconcile later
                else { cache.deletePendingPayment(order.paymentId); v }
            }
        }
    }

    private fun settled(status: PaymentStatus) = when (status) {
        PaymentStatus.SUCCESS -> TopUpResult.Failed("This payment was already completed")
        PaymentStatus.CANCELLED -> TopUpResult.Cancelled
        else -> TopUpResult.Failed("Payment is ${status.name.lowercase()}")
    }

    /** After a crash/restart: ask the server what happened to payments we never saw confirmed. */
    suspend fun reconcilePending(): Int {
        var settledCount = 0
        for (p in cache.pendingPayments()) {
            val s = payments.status(p.paymentId)
            if (s is Outcome.Success && s.value != PaymentStatus.PENDING) {
                cache.deletePendingPayment(p.paymentId)
                settledCount++
            }
        }
        if (settledCount > 0) profile.refreshWallet()
        return settledCount
    }

    fun newIdempotencyKey(): String = "topup-" + UUID.randomUUID()
}

// -------------------------------------------------------------------------------------- Rides
class RideRepository(
    private val rides: RideService,
    private val notifications: NotificationService,
    private val cache: CacheDao,
) {
    private val requestMutex = Mutex()

    val history: Flow<List<RideRecord>> by lazy { cache.rideHistory().map { list -> list.map { it.toUi() } } }

    suspend fun quote(vehicleId: String, pickup: LocationPoint, dropoff: LocationPoint) = rides.quote(vehicleId, pickup, dropoff)

    /**
     * Requests a ride exactly once. The idempotency key is persisted BEFORE the network call, so
     * if the app dies mid-request the retry (see [recover]) reuses it and the server returns the
     * same ride instead of holding the fare twice.
     */
    suspend fun request(quote: RideQuote): Outcome<Ride> = requestMutex.withLock {
        if (cache.pendingRideRequest() != null) {
            return@withLock Outcome.Failure(AppError.Api(409, "REQUEST_IN_FLIGHT", "A ride request is still being confirmed. Please wait."))
        }
        val key = "ride-" + UUID.randomUUID()
        cache.insertPendingRideRequest(PendingRideRequest(key, quote.quoteId, quote.fare.totalPaise, System.currentTimeMillis()))
        val r = rides.request(quote.quoteId, quote.fare.totalPaise, key)
        when {
            r is Outcome.Success -> { cache.deletePendingRideRequest(key); track(r.value) }
            r is Outcome.Failure && !r.error.isRetryable -> cache.deletePendingRideRequest(key)
            // Retryable failure (offline/timeout): keep the pending request for recovery.
        }
        r
    }

    /** Records the ride locally so it survives process death, and caches its snapshot. */
    suspend fun track(ride: Ride) {
        cache.upsertRideIfNewer(ride.toCache())
        if (ride.status.isTerminal) cache.clearActiveRide()
        else cache.setActiveRide(ActiveRidePointer(0, ride.rideId, ride.status.name, ride.version, System.currentTimeMillis()))
    }

    fun observe(rideId: String): Flow<Outcome<Ride>> = notifications.rideUpdates(rideId)

    /**
     * Recovery after restart / reconnect: finishes an interrupted request with its original key,
     * then asks the server for the rider's active ride (the server is the source of truth).
     */
    suspend fun recover(): Outcome<Ride?> {
        cache.pendingRideRequest()?.let { pending ->
            val retry = rides.request(pending.quoteId, pending.expectedTotalPaise, pending.idempotencyKey)
            if (retry is Outcome.Success) track(retry.value)
            if (retry is Outcome.Success || (retry is Outcome.Failure && !retry.error.isRetryable)) {
                cache.deletePendingRideRequest(pending.idempotencyKey)
            }
        }
        val active = rides.active()
        if (active is Outcome.Success) {
            val ride = active.value
            if (ride == null) {
                cache.activeRide()?.let { pointer -> rides.get(pointer.rideId).onSuccess { track(it) } }
                cache.clearActiveRide()
            } else {
                track(ride)
            }
        }
        return active
    }

    suspend fun cancel(rideId: String, reason: String?) = rides.cancel(rideId, reason).onSuccess { track(it) }
    suspend fun rate(rideId: String, stars: Int, tags: List<String>) = rides.rate(rideId, stars, tags).onSuccess { cache.upsertRideIfNewer(it.toCache()) }
    suspend fun refreshHistory(): Outcome<List<Ride>> = rides.history().onSuccess { list -> cache.replaceRides(list.map { it.toCache() }) }
    suspend fun messages(rideId: String) = rides.messages(rideId)
    suspend fun sendMessage(rideId: String, body: String) = rides.sendMessage(rideId, body)
    suspend fun requestMaskedCall(rideId: String) = rides.requestMaskedCall(rideId)
}

internal fun Ride.toCache() = CachedRide(
    rideId = rideId, status = status.name, version = version, vehicleId = vehicleId,
    pickupName = pickup.name, dropName = dropoff.name, distanceMeters = distanceMeters, durationMinutes = durationMinutes,
    fareTotalPaise = fare.totalPaise, chargedPaise = chargedPaise, gstPaise = fare.gstPaise,
    pilotEarningPaise = fare.pilotEarningPaise, platformCommissionPaise = fare.platformCommissionPaise,
    pilotName = pilot?.name, pilotRating = pilot?.rating, vehicleModel = pilot?.vehicleModel, vehiclePlate = pilot?.licensePlate,
    ratingStars = ratingStars, ratingTags = ratingTags, createdAt = createdAt,
)

internal fun CachedRide.toUi(): RideRecord {
    val vehicle = FleetCatalog.VEHICLES.firstOrNull { it.id == vehicleId }
    return RideRecord(
        rideId = rideId, timestamp = createdAt, pickupName = pickupName, dropName = dropName,
        distanceKm = Math.round(distanceMeters / 100.0) / 10.0, durationMin = durationMinutes.toInt(),
        vehicleCategory = vehicle?.categoryName.orEmpty(),
        vehicleModel = listOfNotNull(vehicle?.name, vehicleModel?.let { "($it)" }).joinToString(" "),
        vehiclePlate = vehiclePlate.orEmpty(), pilotName = pilotName ?: "—", pilotRating = pilotRating ?: 0.0,
        grossFare = fareTotalPaise / 100.0, pilotEarnings = pilotEarningPaise / 100.0,
        platformCut = platformCommissionPaise / 100.0, gstTax = gstPaise / 100.0, status = status,
        userRating = ratingStars ?: 0, feedbackTags = ratingTags.orEmpty(), chargedAmount = chargedPaise / 100.0,
    )
}

// -------------------------------------------------------------------------------------- Location / pilots / safety
class LocationRepository(
    private val maps: MapsService,
    private val device: LocationService,
    private val users: UserService,
) {
    fun hasLocationPermission() = device.hasPermission()
    suspend fun search(query: String) = maps.search(query)
    suspend fun pinAt(lat: Double, lng: Double) = maps.reverseGeocode(lat, lng)

    /** GPS fix, reverse geocoded into a place the rider can use as pickup. */
    suspend fun currentPlace(): Outcome<LocationPoint> = when (val fix = device.currentLocation()) {
        is Outcome.Success -> maps.reverseGeocode(fix.value.first, fix.value.second)
            .map { it.copy(source = com.example.domain.LocationSource.GPS) }
        is Outcome.Failure -> fix
    }

    suspend fun savedAddresses(): Outcome<List<SavedAddress>> = users.addresses()
    suspend fun saveAddress(label: String, place: LocationPoint) = users.addAddress(label, place)
    suspend fun deleteAddress(id: String) = users.deleteAddress(id)
}

class PilotRepository(private val pilots: PilotService) {
    /** Minutes until the nearest available pilot for each vehicle class (null = none nearby). */
    suspend fun etaByVehicle(lat: Double, lng: Double): Outcome<Map<String, Int>> = pilots.nearby(lat, lng).map { list ->
        FleetCatalog.VEHICLES.associate { v -> v.id to list.filter { v.id in it.vehicleIds }.minOfOrNull { it.etaMinutes } }
            .filterValues { it != null }.mapValues { it.value!! }
    }
}

class SafetyRepository(private val safety: SafetyService) {
    suspend fun triggerSos(lat: Double?, lng: Double?, rideId: String?): Outcome<SosResult> = safety.triggerSos(lat, lng, rideId)
}
