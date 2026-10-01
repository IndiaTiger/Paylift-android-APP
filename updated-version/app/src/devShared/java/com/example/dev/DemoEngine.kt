package com.example.dev

import com.example.core.AppError
import com.example.core.Outcome
import com.example.domain.FareCalculator
import com.example.domain.FareQuote
import com.example.domain.FleetCatalog
import com.example.domain.LocationPoint
import com.example.domain.LocationSource
import com.example.domain.LocationValidation
import com.example.domain.RideStatus
import com.example.services.AuthService
import com.example.services.CheckoutResult
import com.example.services.LedgerEntry
import com.example.services.LedgerType
import com.example.services.LocationService
import com.example.services.MapsService
import com.example.services.NearbyPilot
import com.example.services.OtpChallenge
import com.example.services.PaymentCheckout
import com.example.services.PaymentOrder
import com.example.services.PaymentService
import com.example.services.PaymentStatus
import com.example.services.PilotService
import com.example.services.ProfilePatch
import com.example.services.Ride
import com.example.services.RideMessage
import com.example.services.RidePilot
import com.example.services.RideQuote
import com.example.services.RideService
import com.example.services.Route
import com.example.services.SafetyService
import com.example.services.SavedAddress
import com.example.services.SosResult
import com.example.services.UserAccount
import com.example.services.UserService
import com.example.services.WalletBalance
import com.example.services.WalletService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * DEMO ENGINE - compiled only into the `debug` and `demo` build types (src/devShared), never
 * into release. It runs the PayLift server rules in-process so the demo APK works on a phone
 * without a backend or any external provider:
 *
 *  * login only with the documented demo credentials (fixed code, 5-attempt lockout, expiry);
 *  * a wallet that starts at 0 and changes only through the ledger rules (top-up credit after a
 *    verified test-gateway signature, ride hold -> capture / release, idempotent references);
 *  * the guarded ride state machine and a pilot simulator that drives it;
 *  * SOS / masked calling report "not configured" exactly like the real backend.
 *
 * Nothing here talks to a payment provider, SMS service or police: it is a simulation for
 * demonstrations, labelled "Demo Mode" in the app.
 */
class DemoEngine(
    private val store: Store,
    private val scope: CoroutineScope,
    private val stepMs: Long = 3_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Persistence of the engine state (survives process death). */
    interface Store {
        fun load(): String?
        fun save(json: String)
    }

    class MemoryStore : Store {
        @Volatile private var data: String? = null
        override fun load() = data
        override fun save(json: String) { data = json }
    }

    companion object {
        const val DEMO_PHONE = "+919999999999"
        const val DEMO_OTP = "123456"
        const val MAX_OTP_ATTEMPTS = 5
        const val START_OTP_MAX_ATTEMPTS = 5
        const val MIN_TOPUP = 100L
        const val MAX_TOPUP = 1_000_000L

        val PLACES = listOf(
            LocationPoint("Clock Tower (Ghanta Ghar)", "City Center, Rajpur Road, Dehradun", 30.3255, 78.0436, "City Center"),
            LocationPoint("Rajpur Road (Pacific Mall)", "Jakhan, Rajpur Road, Dehradun", 30.3580, 78.0720, "Commercial"),
            LocationPoint("ISBT Dehradun", "Inter-State Bus Terminal, Haridwar Bypass", 30.2870, 78.0060, "Transit"),
            LocationPoint("Prem Nagar (Chakrata Road)", "Near Graphic Era & IMA, Dehradun", 30.3340, 77.9620, "Institutional"),
            LocationPoint("Jolly Grant Airport (DED)", "Dehradun Airport, Rishikesh Highway", 30.1900, 78.1800, "Airport"),
            LocationPoint("Sahastradhara Springs", "Sulphur Springs & Ropeway, Sahastradhara Road", 30.3872, 78.1288, "Tourist"),
            LocationPoint("Rispana Pull (Bypass)", "Haridwar-Dehradun Highway Intersection", 30.3015, 78.0645, "Transit"),
            LocationPoint("Ballupur Chowk", "GMS Road / Kaulagarh Junction, Dehradun", 30.3370, 78.0160, "Junction"),
            LocationPoint("Forest Research Institute (FRI)", "Chakrata Road, Kaulagarh, Dehradun", 30.3426, 77.9995, "Heritage"),
            LocationPoint("Paltan Bazaar", "Near Dehradun Railway Station, City Heart", 30.3210, 78.0410, "Market"),
            LocationPoint("Mussoorie Diversion (Malsi)", "Rajpur Foothills & Zoo, Mussoorie Highway", 30.3810, 78.0850, "Foothills"),
        ).map { it.copy(source = LocationSource.DEV_DATA) }

        private data class DemoPilot(val id: String, val name: String, val rating: Double, val trips: Int, val vehicles: Set<String>,
                                     val model: String, val color: String, val plate: String, val initials: String, val lat: Double, val lng: Double)

        private val PILOTS = listOf(
            DemoPilot("demo_plt_1", "Vikram Singh Rawat", 4.92, 1840, setOf("cab_mini", "cab_sedan"), "Swift Dzire Prime", "Arctic White", "UK07-BX-4921", "VR", 30.3290, 78.0480),
            DemoPilot("demo_plt_2", "Aman Negi", 4.88, 960, setOf("moto_commuter", "moto_sports"), "Hero Splendor Pro", "Jet Black", "UK07-CM-1088", "AN", 30.3230, 78.0400),
            DemoPilot("demo_plt_3", "Deepak Joshi", 4.95, 2410, setOf("cab_suv", "cab_luxury"), "Toyota Innova Crysta", "Silver Metallic", "UK07-TA-9930", "DJ", 30.3310, 78.0510),
            DemoPilot("demo_plt_4", "Rahul Chauhan", 4.90, 1150, setOf("moto_electric"), "Ola S1 Pro (EV)", "Electric Blue", "UK07-EV-5512", "RC", 30.3200, 78.0450),
        )
    }

    // ------------------------------------------------------------------ state (guarded by lock)
    private val lock = Any()
    private var gatewayKey = ""
    private var signedIn = false
    private var otpRequestId: String? = null
    private var otpAttempts = 0
    private var otpExpiresAt = 0L
    private var profile = JSONObject()
    private var balance = 0L
    private var held = 0L
    private val ledger = mutableListOf<LedgerEntry>()
    private val payments = linkedMapOf<String, JSONObject>() // by idempotency key
    private val quotes = mutableMapOf<String, RideQuote>()
    private val rides = linkedMapOf<String, JSONObject>()
    private val ridesByKey = mutableMapOf<String, String>()
    private val messages = mutableMapOf<String, MutableList<RideMessage>>()
    private val addresses = mutableListOf<SavedAddress>()
    val sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        restore()
        if (gatewayKey.isEmpty()) {
            // Per-install random key for the simulated gateway's signatures (never hardcoded).
            gatewayKey = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            persist()
        }
        scope.launch {
            while (isActive) {
                delay(stepMs)
                runCatching { simulatorStep() }
            }
        }
    }

    // ------------------------------------------------------------------ ledger rules
    private fun applyLedger(type: LedgerType, amount: Long, reference: String, paymentId: String? = null, rideId: String? = null, description: String): Boolean {
        require(amount > 0)
        if (ledger.any { it.reference == reference }) return false // idempotent replay
        when (type) {
            LedgerType.TOPUP_CREDIT, LedgerType.RIDE_REFUND -> balance += amount
            LedgerType.RIDE_HOLD -> { check(balance - held >= amount) { "INSUFFICIENT_FUNDS" }; held += amount }
            LedgerType.HOLD_RELEASE -> { check(held >= amount); held -= amount }
            LedgerType.RIDE_CAPTURE, LedgerType.CANCELLATION_FEE -> { check(held >= amount); held -= amount; balance -= amount }
            LedgerType.TOPUP_REFUND -> { check(balance - held >= amount); balance -= amount }
            LedgerType.UNKNOWN -> error("unknown")
        }
        ledger += LedgerEntry("le_${UUID.randomUUID()}", type, amount, balance, held, reference, paymentId, rideId, description, now())
        return true
    }

    fun wallet() = synchronized(lock) { WalletBalance(balance, held, balance - held, "INR") }

    /** Fold of the ledger equals the wallet (used by tests). */
    fun reconciles(): Boolean = synchronized(lock) {
        var b = 0L; var h = 0L
        for (e in ledger) when (e.type) {
            LedgerType.TOPUP_CREDIT, LedgerType.RIDE_REFUND -> b += e.amountPaise
            LedgerType.RIDE_HOLD -> h += e.amountPaise
            LedgerType.HOLD_RELEASE -> h -= e.amountPaise
            LedgerType.RIDE_CAPTURE, LedgerType.CANCELLATION_FEE -> { h -= e.amountPaise; b -= e.amountPaise }
            LedgerType.TOPUP_REFUND -> b -= e.amountPaise
            LedgerType.UNKNOWN -> Unit
        }
        b == balance && h == held
    }

    private fun sign(orderId: String, providerPaymentId: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(gatewayKey.toByteArray(), "HmacSHA256")) }
        return mac.doFinal("$orderId|$providerPaymentId".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun api(status: Int, code: String, msg: String) = Outcome.Failure(AppError.Api(status, code, msg))
    private fun requireSession(): Outcome.Failure? = if (signedIn) null else Outcome.Failure(AppError.Unauthorized)

    // ------------------------------------------------------------------ services
    val auth = object : AuthService {
        override val isSignedIn: Boolean get() = synchronized(lock) { signedIn }
        override val sessionExpired: Flow<Unit> = this@DemoEngine.sessionExpired

        override suspend fun requestOtp(phone: String): Outcome<OtpChallenge> = synchronized(lock) {
            if (phone != DEMO_PHONE) {
                return api(503, "AUTH_PROVIDER_NOT_CONFIGURED", "Demo Mode: SMS is not connected. Sign in with the demo number 9999999999.")
            }
            otpRequestId = "otp_${UUID.randomUUID()}"
            otpAttempts = 0
            otpExpiresAt = now() + 5 * 60_000
            // No code is returned: the demo code is documented in demo/README.md.
            Outcome.Success(OtpChallenge(phone, otpRequestId!!, 300, null))
        }

        override suspend fun verifyOtp(challenge: OtpChallenge, otp: String): Outcome<UserAccount> = synchronized(lock) {
            if (challenge.requestId != otpRequestId || now() > otpExpiresAt) return api(401, "OTP_EXPIRED", "OTP expired or already used")
            if (otpAttempts >= MAX_OTP_ATTEMPTS) return api(429, "OTP_LOCKED", "Too many wrong attempts")
            if (otp != DEMO_OTP) { otpAttempts++; return api(401, "OTP_INVALID", "Incorrect OTP") }
            otpRequestId = null
            signedIn = true
            if (!profile.has("phone")) profile.put("phone", DEMO_PHONE)
            persist()
            Outcome.Success(account())
        }

        override suspend fun logout(): Outcome<Unit> = synchronized(lock) { signedIn = false; persist(); Outcome.Success(Unit) }

        override suspend fun deleteAccount(): Outcome<Unit> = synchronized(lock) {
            if (rides.values.any { !RideStatus.parse(it.getString("status")).isTerminal }) return api(409, "ACTIVE_RIDE_EXISTS", "Finish or cancel your active ride first")
            if (balance > 0) return api(409, "WALLET_NOT_EMPTY", "Wallet balance must be withdrawn or refunded before deleting the account")
            profile = JSONObject(); signedIn = false; addresses.clear(); persist()
            Outcome.Success(Unit)
        }
    }

    private fun account() = UserAccount(
        "demo_user", profile.optString("phone", DEMO_PHONE), profile.optString("name"), profile.optString("email"),
        profile.optString("ecName"), profile.optString("ecPhone"), profile.optString("ecRel"), profile.optBoolean("autoDial", false),
    )

    val users = object : UserService {
        override suspend fun me(): Outcome<UserAccount> = synchronized(lock) { requireSession() ?: Outcome.Success(account()) }
        override suspend fun update(patch: ProfilePatch): Outcome<UserAccount> = synchronized(lock) {
            requireSession()?.let { return it }
            patch.name?.let { profile.put("name", it) }
            patch.email?.let { profile.put("email", it) }
            patch.emergencyContactName?.let { profile.put("ecName", it) }
            patch.emergencyContactPhone?.let { profile.put("ecPhone", it) }
            patch.emergencyRelationship?.let { profile.put("ecRel", it) }
            patch.autoDialSos?.let { profile.put("autoDial", it) }
            persist()
            Outcome.Success(account())
        }
        override suspend fun addresses(): Outcome<List<SavedAddress>> = synchronized(lock) { requireSession() ?: Outcome.Success(addresses.toList()) }
        override suspend fun addAddress(label: String, place: LocationPoint): Outcome<SavedAddress> = synchronized(lock) {
            requireSession()?.let { return it }
            SavedAddress("adr_${addresses.size + 1}", label, place.copy(source = LocationSource.SAVED)).also { addresses += it; persist() }.let { Outcome.Success(it) }
        }
        override suspend fun deleteAddress(addressId: String): Outcome<Unit> = synchronized(lock) {
            if (addresses.removeAll { it.addressId == addressId }) { persist(); Outcome.Success(Unit) } else api(404, "NOT_FOUND", "Address not found")
        }
    }

    val walletService = object : WalletService {
        override suspend fun wallet(): Outcome<WalletBalance> = synchronized(lock) { requireSession() ?: Outcome.Success(this@DemoEngine.wallet()) }
        override suspend fun transactions(limit: Int): Outcome<List<LedgerEntry>> = synchronized(lock) {
            requireSession() ?: Outcome.Success(ledger.sortedByDescending { it.createdAt }.take(limit))
        }
    }

    val paymentService = object : PaymentService {
        override suspend fun createOrder(amountPaise: Long, idempotencyKey: String): Outcome<PaymentOrder> = synchronized(lock) {
            requireSession()?.let { return it }
            if (amountPaise !in MIN_TOPUP..MAX_TOPUP) return api(400, "VALIDATION_ERROR", "Amount out of range")
            val p = payments.getOrPut(idempotencyKey) {
                JSONObject().put("id", "pay_${UUID.randomUUID()}").put("order", "order_demo_${UUID.randomUUID()}")
                    .put("amount", amountPaise).put("status", "PENDING")
            }
            if (p.getLong("amount") != amountPaise) return api(409, "IDEMPOTENCY_CONFLICT", "Idempotency key reused with a different amount")
            persist()
            Outcome.Success(p.toOrder())
        }

        override suspend fun verify(order: PaymentOrder, result: CheckoutResult): Outcome<Pair<PaymentStatus, WalletBalance>> = synchronized(lock) {
            requireSession()?.let { return it }
            val p = payments.values.firstOrNull { it.getString("id") == order.paymentId } ?: return api(404, "NOT_FOUND", "Payment not found")
            when (result) {
                is CheckoutResult.Completed -> {
                    if (sign(p.getString("order"), result.providerPaymentId) != result.signature) return api(400, "INVALID_SIGNATURE", "Payment signature verification failed")
                    when (p.getString("status")) {
                        "PENDING" -> {
                            p.put("status", "SUCCESS")
                            applyLedger(LedgerType.TOPUP_CREDIT, p.getLong("amount"), "topup:${p.getString("id")}", paymentId = p.getString("id"), description = "Wallet top-up (demo gateway)")
                        }
                        "SUCCESS" -> Unit // idempotent
                        else -> return api(409, "PAYMENT_NOT_PENDING", "Payment is ${p.getString("status")}")
                    }
                }
                is CheckoutResult.Failed -> if (p.getString("status") == "PENDING") p.put("status", "FAILED")
                CheckoutResult.Cancelled -> if (p.getString("status") == "PENDING") p.put("status", "CANCELLED")
            }
            persist()
            Outcome.Success(PaymentStatus.parse(p.getString("status")) to this@DemoEngine.wallet())
        }

        override suspend fun status(paymentId: String): Outcome<PaymentStatus> = synchronized(lock) {
            payments.values.firstOrNull { it.getString("id") == paymentId }?.let { Outcome.Success(PaymentStatus.parse(it.getString("status"))) }
                ?: api(404, "NOT_FOUND", "Payment not found")
        }
    }

    private fun JSONObject.toOrder() = PaymentOrder(getString("id"), "demo", getString("order"), getLong("amount"), PaymentStatus.parse(getString("status")), null)

    /** Simulated hosted checkout of the demo gateway: signs like a real gateway would. */
    val checkout = object : PaymentCheckout {
        override val providerLabel = "Demo Gateway"
        override val isAvailable = true
        override suspend fun launch(order: PaymentOrder): Outcome<CheckoutResult> = synchronized(lock) {
            val providerPaymentId = "pay_demo_${UUID.randomUUID()}"
            Outcome.Success(CheckoutResult.Completed(providerPaymentId, sign(order.providerOrderId, providerPaymentId)))
        }
    }

    // ------------------------------------------------------------------ maps / routing (demo data, labelled)
    private fun route(o: LocationPoint, d: LocationPoint): Route {
        val straight = LocationValidation.haversineMeters(o.latitude, o.longitude, d.latitude, d.longitude)
        val meters = maxOf(Math.round(straight * 1.28), 200L)
        val minutes = maxOf(((meters / 1000.0 / 24.0) * 60).toLong() + 3, 5L)
        val poly = (0..24).map { i -> val f = i / 24.0; (o.latitude + (d.latitude - o.latitude) * f) to (o.longitude + (d.longitude - o.longitude) * f) }
        return Route(meters, minutes, poly, "demo-approximation")
    }

    val maps = object : MapsService {
        override suspend fun search(query: String): Outcome<List<LocationPoint>> {
            val q = query.trim().lowercase()
            return Outcome.Success(PLACES.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.subtitle.lowercase().contains(q) })
        }
        override suspend fun reverseGeocode(lat: Double, lng: Double): Outcome<LocationPoint> {
            val nearest = PLACES.minBy { LocationValidation.haversineMeters(lat, lng, it.latitude, it.longitude) }
            val d = LocationValidation.haversineMeters(lat, lng, nearest.latitude, nearest.longitude)
            return Outcome.Success(
                if (d <= 500) nearest.copy(latitude = lat, longitude = lng, source = LocationSource.MAP_PIN)
                else LocationPoint("Pinned location", "%.5f, %.5f".format(lat, lng), lat, lng, "Pin", LocationSource.MAP_PIN)
            )
        }
        override suspend fun route(origin: LocationPoint, destination: LocationPoint) = Outcome.Success(this@DemoEngine.route(origin, destination))
    }

    /** No GPS in demo mode: the device location is replaced by the demo city centre (clearly a demo point). */
    val location = object : LocationService {
        override fun hasPermission() = true
        override suspend fun currentLocation(): Outcome<Pair<Double, Double>> = Outcome.Success(PLACES[0].latitude to PLACES[0].longitude)
    }

    // ------------------------------------------------------------------ rides
    private fun quoteFare(vehicleId: String, meters: Long, minutes: Long): FareQuote {
        val v = FleetCatalog.VEHICLES.first { it.id == vehicleId }
        val r = FareCalculator.calculate(FareCalculator.ratesFor(v), meters, minutes)
        return FareQuote(vehicleId, meters, minutes, v.baseFarePaise, v.perKmRatePaise, v.timeRatePerMinPaise, r.distanceChargePaise,
            r.durationChargePaise, v.categoryMultiplierBp, v.luxuryMultiplierBp, v.engineCcFactorBp, r.transitChargePaise, r.subtotalPaise,
            v.platformSafetyFeePaise, r.taxablePaise, r.gstPaise, r.totalPaise, r.pilotEarningPaise, r.platformCommissionPaise)
    }

    private fun transition(ride: JSONObject, to: RideStatus): Boolean {
        val from = RideStatus.parse(ride.getString("status"))
        if (!RideStatus.canTransition(from, to)) return false
        ride.put("status", to.name).put("version", ride.getLong("version") + 1)
        return true
    }

    private fun settleUnfinished(ride: JSONObject) {
        applyLedger(LedgerType.HOLD_RELEASE, ride.getLong("total"), "release:${ride.getString("id")}", rideId = ride.getString("id"), description = "Fare hold released")
        ride.optString("pilot").takeIf { it.isNotEmpty() }?.let { ride.put("pilot", "") }
    }

    private fun JSONObject.toRide(): Ride {
        val pilot = PILOTS.firstOrNull { it.id == optString("pilot") }
        val poly = getJSONArray("poly").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } } }
        val q = quotes[getString("quote")]
        val idx = optInt("progress", 0)
        val status = RideStatus.parse(getString("status"))
        val pilotPos = when {
            pilot == null -> null
            status == RideStatus.IN_PROGRESS || status == RideStatus.COMPLETED -> poly[idx.coerceIn(0, poly.lastIndex)]
            status == RideStatus.ARRIVED || status == RideStatus.OTP_VERIFIED -> poly.first()
            else -> pilot.lat to pilot.lng
        }
        return Ride(
            rideId = getString("id"), status = status, version = getLong("version"), vehicleId = getString("vehicle"),
            pickup = q!!.pickup, dropoff = q.dropoff, distanceMeters = q.route.distanceMeters, durationMinutes = q.route.durationMinutes,
            polyline = poly, fare = q.fare, chargedPaise = optLong("charged", 0), startOtp = if (status.isTerminal) null else getString("otp"),
            pilot = pilot?.let { RidePilot(it.id, it.name, it.rating, it.trips, it.model, it.color, it.plate, it.initials, pilotPos) },
            progressFraction = when (status) { RideStatus.COMPLETED -> 1.0; RideStatus.IN_PROGRESS -> idx / poly.lastIndex.toDouble(); else -> 0.0 },
            ratingStars = optInt("stars", 0).takeIf { it > 0 }, ratingTags = optString("tags").takeIf { it.isNotEmpty() },
            createdAt = getLong("created"),
        )
    }

    val rideService = object : RideService {
        override suspend fun quote(vehicleId: String, pickup: LocationPoint, dropoff: LocationPoint): Outcome<RideQuote> = synchronized(lock) {
            requireSession()?.let { return it }
            if (LocationValidation.haversineMeters(pickup.latitude, pickup.longitude, dropoff.latitude, dropoff.longitude) < LocationValidation.MIN_TRIP_METERS) {
                return api(422, "PICKUP_EQUALS_DROPOFF", "Pickup and drop-off must be at least 200 m apart")
            }
            val r = route(pickup, dropoff)
            val q = RideQuote("qt_${UUID.randomUUID()}", vehicleId, pickup, dropoff, quoteFare(vehicleId, r.distanceMeters, r.durationMinutes), r, now() + 5 * 60_000)
            quotes[q.quoteId] = q
            Outcome.Success(q)
        }

        override suspend fun request(quoteId: String, expectedTotalPaise: Long, idempotencyKey: String): Outcome<Ride> = synchronized(lock) {
            requireSession()?.let { return it }
            ridesByKey[idempotencyKey]?.let { return Outcome.Success(rides.getValue(it).toRide()) }
            val q = quotes[quoteId] ?: return api(404, "QUOTE_NOT_FOUND", "Quote not found")
            if (q.expiresAt < now()) return api(410, "QUOTE_EXPIRED", "Fare quote expired, please refresh")
            if (expectedTotalPaise != q.fare.totalPaise) return api(409, "FARE_CHANGED", "Displayed fare differs from the quoted fare")
            if (rides.values.any { it.getString("quote") == quoteId }) return api(409, "QUOTE_USED", "Quote already used")
            if (rides.values.any { !RideStatus.parse(it.getString("status")).isTerminal }) return api(409, "ACTIVE_RIDE_EXISTS", "You already have an active ride")
            if (balance - held < q.fare.totalPaise) return api(409, "INSUFFICIENT_FUNDS", "Insufficient wallet balance")
            val id = "ride_${UUID.randomUUID().toString().take(8)}"
            val poly = JSONArray().apply { q.route.polyline.forEach { put(JSONArray().put(it.first).put(it.second)) } }
            val ride = JSONObject().put("id", id).put("status", RideStatus.REQUESTED.name).put("version", 0L).put("vehicle", q.vehicleId)
                .put("quote", quoteId).put("total", q.fare.totalPaise).put("otp", "%04d".format(SecureRandom().nextInt(10_000)))
                .put("poly", poly).put("created", now()).put("otpFails", 0)
            applyLedger(LedgerType.RIDE_HOLD, q.fare.totalPaise, "hold:$id", rideId = id, description = "Fare held for ride")
            transition(ride, RideStatus.SEARCHING)
            rides[id] = ride
            ridesByKey[idempotencyKey] = id
            persist()
            Outcome.Success(ride.toRide())
        }

        override suspend fun get(rideId: String): Outcome<Ride> = synchronized(lock) {
            requireSession() ?: rides[rideId]?.let { Outcome.Success(it.toRide()) } ?: api(404, "NOT_FOUND", "Ride not found")
        }

        override suspend fun active(): Outcome<Ride?> = synchronized(lock) {
            requireSession() ?: Outcome.Success(rides.values.firstOrNull { !RideStatus.parse(it.getString("status")).isTerminal }?.toRide())
        }

        override suspend fun history(limit: Int): Outcome<List<Ride>> = synchronized(lock) {
            requireSession() ?: Outcome.Success(rides.values.map { it.toRide() }.sortedByDescending { it.createdAt }.take(limit))
        }

        override suspend fun cancel(rideId: String, reason: String?): Outcome<Ride> = synchronized(lock) {
            requireSession()?.let { return it }
            val ride = rides[rideId] ?: return api(404, "NOT_FOUND", "Ride not found")
            if (!transition(ride, RideStatus.CANCELLED)) return api(409, "INVALID_TRANSITION", "This ride can no longer be cancelled")
            settleUnfinished(ride)
            persist()
            Outcome.Success(ride.toRide())
        }

        override suspend fun rate(rideId: String, stars: Int, tags: List<String>): Outcome<Ride> = synchronized(lock) {
            val ride = rides[rideId] ?: return api(404, "NOT_FOUND", "Ride not found")
            if (ride.getString("status") != RideStatus.COMPLETED.name || ride.optInt("stars", 0) > 0) return api(409, "ALREADY_RATED", "Ride cannot be rated")
            if (stars !in 1..5) return api(400, "VALIDATION_ERROR", "stars must be 1..5")
            ride.put("stars", stars).put("tags", tags.joinToString(", ")).put("version", ride.getLong("version") + 1)
            persist()
            Outcome.Success(ride.toRide())
        }

        override suspend fun messages(rideId: String): Outcome<List<RideMessage>> = synchronized(lock) { Outcome.Success(messages[rideId].orEmpty().toList()) }

        override suspend fun sendMessage(rideId: String, body: String): Outcome<RideMessage> = synchronized(lock) {
            val ride = rides[rideId] ?: return api(404, "NOT_FOUND", "Ride not found")
            if (RideStatus.parse(ride.getString("status")).isTerminal) return api(409, "RIDE_ENDED", "Ride has ended")
            RideMessage("msg_${UUID.randomUUID()}", "rider", body.take(500), now()).also { messages.getOrPut(rideId) { mutableListOf() } += it }.let { Outcome.Success(it) }
        }

        override suspend fun requestMaskedCall(rideId: String): Outcome<Unit> =
            Outcome.Failure(AppError.NotConfigured("CALL_PROVIDER", "Masked calling is not configured"))
    }

    val pilots = object : PilotService {
        override suspend fun nearby(lat: Double, lng: Double, vehicleId: String?): Outcome<List<NearbyPilot>> = synchronized(lock) {
            val busy = rides.values.filter { !RideStatus.parse(it.getString("status")).isTerminal }.map { it.optString("pilot") }.toSet()
            Outcome.Success(PILOTS.filter { it.id !in busy && (vehicleId == null || vehicleId in it.vehicles) }.map {
                val d = LocationValidation.haversineMeters(lat, lng, it.lat, it.lng).toLong()
                NearbyPilot(it.id, it.vehicles.toList(), it.lat to it.lng, d, maxOf(1, Math.round(d / 1000.0 / 20.0 * 60).toInt()))
            })
        }
        private fun notAPilot() = api(403, "FORBIDDEN", "Pilot account required")
        override suspend fun setAvailability(available: Boolean) = notAPilot()
        override suspend fun updateLocation(lat: Double, lng: Double) = notAPilot()
        override suspend fun accept(rideId: String) = notAPilot()
        override suspend fun markArriving(rideId: String) = notAPilot()
        override suspend fun markArrived(rideId: String) = notAPilot()
        override suspend fun verifyStartOtp(rideId: String, otp: String) = notAPilot()
        override suspend fun start(rideId: String) = notAPilot()
        override suspend fun complete(rideId: String) = notAPilot()
    }

    val safety = object : SafetyService {
        override suspend fun triggerSos(lat: Double?, lng: Double?, rideId: String?): Outcome<SosResult> =
            Outcome.Success(SosResult.Unavailable("Demo Mode: emergency dispatch is not connected. Call 112 directly."))
    }

    /**
     * Simulated pilot side, one guarded transition per step. The start code is checked with the
     * same attempt limit as the backend (the simulated pilot always enters the right code).
     */
    fun simulatorStep() = synchronized(lock) {
        val ride = rides.values.firstOrNull { !RideStatus.parse(it.getString("status")).isTerminal } ?: return
        when (RideStatus.parse(ride.getString("status"))) {
            RideStatus.SEARCHING -> {
                val pilot = PILOTS.firstOrNull { ride.getString("vehicle") in it.vehicles } ?: return
                ride.put("pilot", pilot.id)
                transition(ride, RideStatus.ASSIGNED)
            }
            RideStatus.ASSIGNED -> transition(ride, RideStatus.PILOT_ACCEPTED)
            RideStatus.PILOT_ACCEPTED -> transition(ride, RideStatus.PILOT_ARRIVING)
            RideStatus.PILOT_ARRIVING -> transition(ride, RideStatus.ARRIVED)
            RideStatus.ARRIVED -> {
                if (ride.optInt("otpFails", 0) < START_OTP_MAX_ATTEMPTS) transition(ride, RideStatus.OTP_VERIFIED)
            }
            RideStatus.OTP_VERIFIED -> transition(ride, RideStatus.IN_PROGRESS).also { ride.put("progress", 0) }
            RideStatus.IN_PROGRESS -> {
                val last = ride.getJSONArray("poly").length() - 1
                val next = minOf(ride.optInt("progress", 0) + 4, last)
                ride.put("progress", next).put("version", ride.getLong("version") + 1)
                if (next == last && transition(ride, RideStatus.COMPLETED)) {
                    applyLedger(LedgerType.RIDE_CAPTURE, ride.getLong("total"), "capture:${ride.getString("id")}", rideId = ride.getString("id"), description = "Ride fare charged")
                    ride.put("charged", ride.getLong("total"))
                }
            }
            else -> Unit
        }
        persist()
    }

    // ------------------------------------------------------------------ persistence
    private fun persist() {
        val o = JSONObject()
            .put("key", gatewayKey).put("signedIn", signedIn).put("profile", profile).put("balance", balance).put("held", held)
            .put("ledger", JSONArray().apply {
                ledger.forEach {
                    put(JSONObject().put("id", it.id).put("type", it.type.name).put("amount", it.amountPaise).put("bal", it.balanceAfterPaise)
                        .put("held", it.heldAfterPaise).put("ref", it.reference).put("pay", it.paymentId ?: "").put("ride", it.rideId ?: "")
                        .put("desc", it.description).put("at", it.createdAt))
                }
            })
            .put("payments", JSONObject().apply { payments.forEach { (k, v) -> put(k, v) } })
            .put("rides", JSONObject().apply { rides.forEach { (k, v) -> put(k, v) } })
            .put("ridesByKey", JSONObject(ridesByKey as Map<*, *>))
            .put("quotes", JSONArray().apply {
                quotes.values.forEach { q ->
                    put(JSONObject().put("id", q.quoteId).put("vehicle", q.vehicleId).put("exp", q.expiresAt)
                        .put("p", JSONArray().put(q.pickup.name).put(q.pickup.subtitle).put(q.pickup.latitude).put(q.pickup.longitude))
                        .put("d", JSONArray().put(q.dropoff.name).put(q.dropoff.subtitle).put(q.dropoff.latitude).put(q.dropoff.longitude)))
                }
            })
        store.save(o.toString())
    }

    private fun restore() {
        val raw = store.load() ?: return
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        gatewayKey = o.optString("key")
        signedIn = o.optBoolean("signedIn")
        profile = o.optJSONObject("profile") ?: JSONObject()
        balance = o.optLong("balance")
        held = o.optLong("held")
        o.optJSONArray("ledger")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                ledger += LedgerEntry(it.getString("id"), LedgerType.valueOf(it.getString("type")), it.getLong("amount"), it.getLong("bal"), it.getLong("held"),
                    it.getString("ref"), it.optString("pay").ifEmpty { null }, it.optString("ride").ifEmpty { null }, it.getString("desc"), it.getLong("at"))
            }
        }
        o.optJSONObject("payments")?.let { p -> p.keys().forEach { payments[it] = p.getJSONObject(it) } }
        o.optJSONObject("rides")?.let { r -> r.keys().forEach { rides[it] = r.getJSONObject(it) } }
        o.optJSONObject("ridesByKey")?.let { r -> r.keys().forEach { ridesByKey[it] = r.getString(it) } }
        o.optJSONArray("quotes")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { q ->
                fun place(arr: JSONArray) = LocationPoint(arr.getString(0), arr.getString(1), arr.getDouble(2), arr.getDouble(3), source = LocationSource.DEV_DATA)
                val p = place(q.getJSONArray("p")); val d = place(q.getJSONArray("d"))
                val r = route(p, d)
                quotes[q.getString("id")] = RideQuote(q.getString("id"), q.getString("vehicle"), p, d, quoteFare(q.getString("vehicle"), r.distanceMeters, r.durationMinutes), r, q.getLong("exp"))
            }
        }
    }
}
