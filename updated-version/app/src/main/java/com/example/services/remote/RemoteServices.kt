package com.example.services.remote

import com.example.api.ApiClient
import com.example.api.dto.AvailabilityBody
import com.example.api.dto.CancelRideBody
import com.example.api.dto.CreateOrderBody
import com.example.api.dto.GeocodeBody
import com.example.api.dto.LatLngDto
import com.example.api.dto.NewAddressBody
import com.example.api.dto.NewMessageBody
import com.example.api.dto.QuoteBody
import com.example.api.dto.RatingBody
import com.example.api.dto.RefreshBody
import com.example.api.dto.RequestOtpBody
import com.example.api.dto.RequestRideBody
import com.example.api.dto.RouteBody
import com.example.api.dto.SosBody
import com.example.api.dto.UserPatchBody
import com.example.api.dto.VerifyOtpBody
import com.example.api.dto.VerifyPaymentBody
import com.example.api.dto.VerifyStartOtpBody
import com.example.auth.TokenStore
import com.example.auth.Tokens
import com.example.core.AppError
import com.example.core.Outcome
import com.example.core.map
import com.example.domain.LocationPoint
import com.example.domain.LocationSource
import com.example.services.AuthService
import com.example.services.CheckoutResult
import com.example.services.LedgerEntry
import com.example.services.MapsService
import com.example.services.NearbyPilot
import com.example.services.NotificationService
import com.example.services.OtpChallenge
import com.example.services.PaymentOrder
import com.example.services.PaymentService
import com.example.services.PaymentStatus
import com.example.services.PilotService
import com.example.services.ProfilePatch
import com.example.services.Ride
import com.example.services.RideMessage
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class RemoteAuthService(private val client: ApiClient, private val tokens: TokenStore) : AuthService {
    override val isSignedIn: Boolean get() = tokens.read() != null
    override val sessionExpired: Flow<Unit> = client.sessionExpired

    override suspend fun requestOtp(phone: String) = client.call { requestOtp(RequestOtpBody(phone)) }
        .map { OtpChallenge(phone, it.requestId, it.expiresInSec, it.devOtp) }

    override suspend fun verifyOtp(challenge: OtpChallenge, otp: String): Outcome<UserAccount> =
        client.call { verifyOtp(VerifyOtpBody(challenge.phone, challenge.requestId, otp)) }.map { s ->
            tokens.write(Tokens(s.accessToken, s.refreshToken))
            s.user?.toDomain() ?: UserAccount("", challenge.phone, "", "", "", "", "", false)
        }

    override suspend fun logout(): Outcome<Unit> {
        val current = tokens.read()
        // Local sign-out always happens; the server revocation is best effort and its result is reported.
        tokens.clear()
        return if (current == null) Outcome.Success(Unit) else client.call { logout(RefreshBody(current.refreshToken)) }.map { }
    }

    override suspend fun deleteAccount(): Outcome<Unit> = client.call { deleteMe() }.map { tokens.clear() }
}

class RemoteUserService(private val client: ApiClient) : UserService {
    override suspend fun me() = client.call { me() }.map { it.toDomain() }
    override suspend fun update(patch: ProfilePatch) = client.call {
        updateMe(UserPatchBody(patch.name, patch.email, patch.emergencyContactName, patch.emergencyContactPhone, patch.emergencyRelationship, patch.autoDialSos))
    }.map { it.toDomain() }
    override suspend fun addresses(): Outcome<List<SavedAddress>> = client.call { addresses() }.map { r -> r.addresses.map { it.toDomain() } }
    override suspend fun addAddress(label: String, place: LocationPoint) = client.call {
        addAddress(NewAddressBody(label, place.name, place.subtitle, place.latitude, place.longitude))
    }.map { it.toDomain() }
    override suspend fun deleteAddress(addressId: String) = client.call { deleteAddress(addressId) }.map { }
}

class RemoteWalletService(private val client: ApiClient) : WalletService {
    override suspend fun wallet(): Outcome<WalletBalance> = client.call { wallet() }.map { it.toDomain() }
    override suspend fun transactions(limit: Int): Outcome<List<LedgerEntry>> =
        client.call { walletTransactions(limit) }.map { r -> r.transactions.map { it.toDomain() } }
}

class RemotePaymentService(private val client: ApiClient) : PaymentService {
    override suspend fun createOrder(amountPaise: Long, idempotencyKey: String): Outcome<PaymentOrder> =
        client.call { createPaymentOrder(idempotencyKey, CreateOrderBody(amountPaise)) }.map { it.toDomain() }

    override suspend fun verify(order: PaymentOrder, result: CheckoutResult): Outcome<Pair<PaymentStatus, WalletBalance>> {
        val body = when (result) {
            is CheckoutResult.Completed -> VerifyPaymentBody(order.paymentId, "SUCCESS", result.providerPaymentId, result.signature)
            is CheckoutResult.Failed -> VerifyPaymentBody(order.paymentId, "FAILED", reason = result.reason)
            CheckoutResult.Cancelled -> VerifyPaymentBody(order.paymentId, "CANCELLED")
        }
        return client.call { verifyPayment(body) }.map { PaymentStatus.parse(it.payment.status) to it.wallet.toDomain() }
    }

    override suspend fun status(paymentId: String) = client.call { payment(paymentId) }.map { PaymentStatus.parse(it.status) }
}

class RemoteMapsService(private val client: ApiClient) : MapsService {
    override suspend fun search(query: String) = client.call { geocode(GeocodeBody(query)) }.map { r -> r.results.map { it.toDomain() } }
    override suspend fun reverseGeocode(lat: Double, lng: Double) =
        client.call { reverseGeocode(LatLngDto(lat, lng)) }.map { it.toDomain(LocationSource.MAP_PIN) }
    override suspend fun route(origin: LocationPoint, destination: LocationPoint): Outcome<Route> =
        client.call { route(RouteBody(LatLngDto(origin.latitude, origin.longitude), LatLngDto(destination.latitude, destination.longitude))) }
            .map { r -> Route(r.distanceMeters, r.durationMinutes, r.polyline.map { it.lat to it.lng }, r.source) }
}

class RemoteRideService(private val client: ApiClient) : RideService {
    override suspend fun quote(vehicleId: String, pickup: LocationPoint, dropoff: LocationPoint): Outcome<RideQuote> {
        val result = client.call { quote(QuoteBody(vehicleId, pickup.toDto(), dropoff.toDto())) }
        if (result !is Outcome.Success) return result as Outcome.Failure
        val q = result.value
        val fare = q.fare.toDomain()
        // Refuse to display a quote whose components do not add up.
        if (!fare.isConsistent()) return Outcome.Failure(AppError.Unexpected("Received an inconsistent fare quote"))
        return Outcome.Success(
            RideQuote(q.quoteId, q.vehicleId, pickup, dropoff, fare,
                Route(q.distanceMeters, q.durationMinutes, q.route.polyline.map { it.lat to it.lng }, q.route.source), q.expiresAt)
        )
    }

    override suspend fun request(quoteId: String, expectedTotalPaise: Long, idempotencyKey: String) =
        client.call { requestRide(idempotencyKey, RequestRideBody(quoteId, expectedTotalPaise)) }.map { it.toDomain() }
    override suspend fun get(rideId: String) = client.call { ride(rideId) }.map { it.toDomain() }
    override suspend fun active(): Outcome<Ride?> = client.call { activeRide() }.map { it.ride?.toDomain() }
    override suspend fun history(limit: Int) = client.call { rides(limit) }.map { r -> r.rides.map { it.toDomain() } }
    override suspend fun cancel(rideId: String, reason: String?) = client.call { cancelRide(rideId, CancelRideBody(reason)) }.map { it.toDomain() }
    override suspend fun rate(rideId: String, stars: Int, tags: List<String>) = client.call { rateRide(rideId, RatingBody(stars, tags)) }.map { it.toDomain() }
    override suspend fun messages(rideId: String) = client.call { messages(rideId) }.map { r -> r.messages.map { RideMessage(it.id, it.senderRole, it.body, it.createdAt) } }
    override suspend fun sendMessage(rideId: String, body: String) = client.call { sendMessage(rideId, NewMessageBody(body)) }.map { RideMessage(it.id, it.senderRole, it.body, it.createdAt) }
    override suspend fun requestMaskedCall(rideId: String) = client.call { requestMaskedCall(rideId) }.map { }
}

class RemotePilotService(private val client: ApiClient) : PilotService {
    override suspend fun nearby(lat: Double, lng: Double, vehicleId: String?): Outcome<List<NearbyPilot>> =
        client.call { nearbyPilots(lat, lng, vehicleId) }.map { r ->
            r.pilots.map { NearbyPilot(it.pilotId, it.vehicleIds, it.location.lat to it.location.lng, it.distanceMeters, it.etaMinutes) }
        }
    override suspend fun setAvailability(available: Boolean) = client.call { setPilotAvailability(AvailabilityBody(if (available) "AVAILABLE" else "OFFLINE")) }.map { }
    override suspend fun updateLocation(lat: Double, lng: Double) = client.call { updatePilotLocation(LatLngDto(lat, lng)) }.map { }
    override suspend fun accept(rideId: String) = client.call { acceptRide(rideId) }.map { it.toDomain() }
    override suspend fun markArriving(rideId: String) = client.call { markArriving(rideId) }.map { it.toDomain() }
    override suspend fun markArrived(rideId: String) = client.call { markArrived(rideId) }.map { it.toDomain() }
    override suspend fun verifyStartOtp(rideId: String, otp: String) = client.call { verifyStartOtp(rideId, VerifyStartOtpBody(otp)) }.map { it.toDomain() }
    override suspend fun start(rideId: String) = client.call { startRide(rideId) }.map { it.toDomain() }
    override suspend fun complete(rideId: String) = client.call { completeRide(rideId) }.map { it.toDomain() }
}

/** Ride updates by polling (no push provider configured). */
class PollingNotificationService(private val rides: RideService, private val intervalMs: Long = 2000) : NotificationService {
    override fun rideUpdates(rideId: String): Flow<Outcome<Ride>> = flow {
        while (true) {
            val r = rides.get(rideId)
            emit(r)
            if (r is Outcome.Success && r.value.status.isTerminal) break
            delay(if (r is Outcome.Failure) intervalMs * 2 else intervalMs)
        }
    }
}

class RemoteSafetyService(private val client: ApiClient) : SafetyService {
    override suspend fun triggerSos(lat: Double?, lng: Double?, rideId: String?): Outcome<SosResult> =
        when (val r = client.call { sos(SosBody(lat, lng, rideId)) }) {
            is Outcome.Success -> Outcome.Success(SosResult.Dispatched)
            is Outcome.Failure -> when (val e = r.error) {
                is AppError.NotConfigured -> Outcome.Success(SosResult.Unavailable(e.message))
                else -> r
            }
        }
}
