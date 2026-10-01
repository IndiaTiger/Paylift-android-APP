package com.example.services

import com.example.core.Outcome
import com.example.domain.FareQuote
import com.example.domain.LocationPoint
import com.example.domain.RideStatus
import kotlinx.coroutines.flow.Flow

// Service interfaces. The UI/ViewModel never talks to these directly - it goes through use cases
// and repositories. Each interface has a Remote* implementation (backend HTTP) and fakes in tests.
// External providers (SMS, payment gateway SDK, Maps SDK, push) plug in behind these interfaces.

// ------------------------------------------------------------------------------ Auth
data class OtpChallenge(val phone: String, val requestId: String, val expiresInSec: Int, val devOtp: String?)

interface AuthService {
    val isSignedIn: Boolean
    /** Emits when the session is revoked/expired server-side and could not be refreshed. */
    val sessionExpired: Flow<Unit>
    suspend fun requestOtp(phone: String): Outcome<OtpChallenge>
    suspend fun verifyOtp(challenge: OtpChallenge, otp: String): Outcome<UserAccount>
    suspend fun logout(): Outcome<Unit>
    suspend fun deleteAccount(): Outcome<Unit>
}

// ------------------------------------------------------------------------------ User
data class UserAccount(
    val userId: String,
    val phone: String,
    val name: String,
    val email: String,
    val emergencyContactName: String,
    val emergencyContactPhone: String,
    val emergencyRelationship: String,
    val autoDialSos: Boolean,
)

data class ProfilePatch(
    val name: String? = null,
    val email: String? = null,
    val emergencyContactName: String? = null,
    val emergencyContactPhone: String? = null,
    val emergencyRelationship: String? = null,
    val autoDialSos: Boolean? = null,
)

data class SavedAddress(val addressId: String, val label: String, val place: LocationPoint)

interface UserService {
    suspend fun me(): Outcome<UserAccount>
    suspend fun update(patch: ProfilePatch): Outcome<UserAccount>
    suspend fun addresses(): Outcome<List<SavedAddress>>
    suspend fun addAddress(label: String, place: LocationPoint): Outcome<SavedAddress>
    suspend fun deleteAddress(addressId: String): Outcome<Unit>
}

// ------------------------------------------------------------------------------ Wallet
data class WalletBalance(val balancePaise: Long, val heldPaise: Long, val availablePaise: Long, val currency: String)

enum class LedgerType { TOPUP_CREDIT, RIDE_HOLD, HOLD_RELEASE, RIDE_CAPTURE, CANCELLATION_FEE, RIDE_REFUND, TOPUP_REFUND, UNKNOWN }

data class LedgerEntry(
    val id: String,
    val type: LedgerType,
    val amountPaise: Long,
    val balanceAfterPaise: Long,
    val heldAfterPaise: Long,
    val reference: String,
    val paymentId: String?,
    val rideId: String?,
    val description: String,
    val createdAt: Long,
)

interface WalletService {
    suspend fun wallet(): Outcome<WalletBalance>
    suspend fun transactions(limit: Int = 50): Outcome<List<LedgerEntry>>
}

// ------------------------------------------------------------------------------ Payments
enum class PaymentStatus { PENDING, SUCCESS, FAILED, CANCELLED, REFUNDED;
    companion object { fun parse(s: String) = entries.firstOrNull { it.name == s } ?: FAILED }
}

data class PaymentOrder(
    val paymentId: String,
    val provider: String,
    val providerOrderId: String,
    val amountPaise: Long,
    val status: PaymentStatus,
    val publicKey: String?,
)

/** What the payment provider's checkout returned to the app. The server decides what it means. */
sealed interface CheckoutResult {
    data class Completed(val providerPaymentId: String, val signature: String) : CheckoutResult
    data class Failed(val reason: String) : CheckoutResult
    data object Cancelled : CheckoutResult
}

interface PaymentService {
    suspend fun createOrder(amountPaise: Long, idempotencyKey: String): Outcome<PaymentOrder>
    /** Sends the checkout result to the server for signature verification + ledger credit. */
    suspend fun verify(order: PaymentOrder, result: CheckoutResult): Outcome<Pair<PaymentStatus, WalletBalance>>
    suspend fun status(paymentId: String): Outcome<PaymentStatus>
}

/**
 * Launches the payment provider's checkout UI/SDK for an order. Production must use the real
 * gateway SDK with PAYMENT_PUBLIC_KEY; the test gateway implementation exists only in the debug
 * source set.
 */
interface PaymentCheckout {
    val providerLabel: String
    val isAvailable: Boolean
    suspend fun launch(order: PaymentOrder): Outcome<CheckoutResult>
}

// ------------------------------------------------------------------------------ Location / maps
data class Route(val distanceMeters: Long, val durationMinutes: Long, val polyline: List<Pair<Double, Double>>, val source: String)

/** Device location (GPS). */
interface LocationService {
    /** Null when permission is missing or no fix is available. */
    suspend fun currentLocation(): Outcome<Pair<Double, Double>>
    fun hasPermission(): Boolean
}

/** Geocoding / routing provider (proxied through the backend so keys stay server-side). */
interface MapsService {
    suspend fun search(query: String): Outcome<List<LocationPoint>>
    suspend fun reverseGeocode(lat: Double, lng: Double): Outcome<LocationPoint>
    suspend fun route(origin: LocationPoint, destination: LocationPoint): Outcome<Route>
}

// ------------------------------------------------------------------------------ Rides / pilots
data class RideQuote(
    val quoteId: String,
    val vehicleId: String,
    val pickup: LocationPoint,
    val dropoff: LocationPoint,
    val fare: FareQuote,
    val route: Route,
    val expiresAt: Long,
)

data class RidePilot(
    val pilotId: String,
    val name: String,
    val rating: Double,
    val totalTrips: Int,
    val vehicleModel: String,
    val vehicleColor: String,
    val licensePlate: String,
    val avatarInitials: String,
    val location: Pair<Double, Double>?,
)

data class Ride(
    val rideId: String,
    val status: RideStatus,
    val version: Long,
    val vehicleId: String,
    val pickup: LocationPoint,
    val dropoff: LocationPoint,
    val distanceMeters: Long,
    val durationMinutes: Long,
    val polyline: List<Pair<Double, Double>>,
    val fare: FareQuote,
    val chargedPaise: Long,
    val startOtp: String?,
    val pilot: RidePilot?,
    val progressFraction: Double,
    val ratingStars: Int?,
    val ratingTags: String?,
    val createdAt: Long,
)

data class RideMessage(val id: String, val senderRole: String, val body: String, val createdAt: Long)

interface RideService {
    suspend fun quote(vehicleId: String, pickup: LocationPoint, dropoff: LocationPoint): Outcome<RideQuote>
    /** Requests a ride for a server quote. [expectedTotalPaise] is the fare shown to the rider; the server rejects a mismatch. */
    suspend fun request(quoteId: String, expectedTotalPaise: Long, idempotencyKey: String): Outcome<Ride>
    suspend fun get(rideId: String): Outcome<Ride>
    suspend fun active(): Outcome<Ride?>
    suspend fun history(limit: Int = 50): Outcome<List<Ride>>
    suspend fun cancel(rideId: String, reason: String?): Outcome<Ride>
    suspend fun rate(rideId: String, stars: Int, tags: List<String>): Outcome<Ride>
    suspend fun messages(rideId: String): Outcome<List<RideMessage>>
    suspend fun sendMessage(rideId: String, body: String): Outcome<RideMessage>
    suspend fun requestMaskedCall(rideId: String): Outcome<Unit>
}

data class NearbyPilot(val pilotId: String, val vehicleIds: List<String>, val location: Pair<Double, Double>, val distanceMeters: Long, val etaMinutes: Int)

/** Pilot-side operations and rider-side pilot discovery. */
interface PilotService {
    suspend fun nearby(lat: Double, lng: Double, vehicleId: String? = null): Outcome<List<NearbyPilot>>
    suspend fun setAvailability(available: Boolean): Outcome<Unit>
    suspend fun updateLocation(lat: Double, lng: Double): Outcome<Unit>
    suspend fun accept(rideId: String): Outcome<Ride>
    suspend fun markArriving(rideId: String): Outcome<Ride>
    suspend fun markArrived(rideId: String): Outcome<Ride>
    suspend fun verifyStartOtp(rideId: String, otp: String): Outcome<Ride>
    suspend fun start(rideId: String): Outcome<Ride>
    suspend fun complete(rideId: String): Outcome<Ride>
}

// ------------------------------------------------------------------------------ Notifications / safety
interface NotificationService {
    /** Ride status updates. Without a push provider this is backed by polling. */
    fun rideUpdates(rideId: String): Flow<Outcome<Ride>>
}

sealed interface SosResult {
    /** An emergency integration actually accepted the alert. */
    data object Dispatched : SosResult
    /** No integration exists; nothing was transmitted. */
    data class Unavailable(val message: String) : SosResult
}

interface SafetyService {
    suspend fun triggerSos(lat: Double?, lng: Double?, rideId: String?): Outcome<SosResult>
}
