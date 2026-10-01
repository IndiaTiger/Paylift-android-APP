package com.example.api.dto

import com.squareup.moshi.JsonClass

// Wire contracts. Must stay in sync with docs/API.md and backend/src. Money is integer paise.

@JsonClass(generateAdapter = true) data class ErrorEnvelope(val error: ErrorBody?)
@JsonClass(generateAdapter = true) data class ErrorBody(val code: String, val message: String)

// ---- Auth
@JsonClass(generateAdapter = true) data class RequestOtpBody(val phone: String)
@JsonClass(generateAdapter = true) data class RequestOtpResponse(val requestId: String, val expiresInSec: Int, val devOtp: String? = null)
@JsonClass(generateAdapter = true) data class VerifyOtpBody(val phone: String, val requestId: String, val otp: String)
@JsonClass(generateAdapter = true) data class SessionResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Int,
    val isNewUser: Boolean? = null,
    val user: UserDto? = null,
)
@JsonClass(generateAdapter = true) data class RefreshBody(val refreshToken: String)

// ---- Users
@JsonClass(generateAdapter = true) data class UserDto(
    val userId: String,
    val phone: String?,
    val role: String,
    val name: String,
    val email: String,
    val emergencyContactName: String,
    val emergencyContactPhone: String,
    val emergencyRelationship: String,
    val autoDialSos: Boolean,
)
/** Partial update: null fields are omitted from the JSON and left unchanged by the server. */
@JsonClass(generateAdapter = true) data class UserPatchBody(
    val name: String? = null,
    val email: String? = null,
    val emergencyContactName: String? = null,
    val emergencyContactPhone: String? = null,
    val emergencyRelationship: String? = null,
    val autoDialSos: Boolean? = null,
)
@JsonClass(generateAdapter = true) data class AddressDto(
    val addressId: String, val label: String, val name: String, val subtitle: String, val lat: Double, val lng: Double,
)
@JsonClass(generateAdapter = true) data class AddressListResponse(val addresses: List<AddressDto>)
@JsonClass(generateAdapter = true) data class NewAddressBody(val label: String, val name: String, val subtitle: String, val lat: Double, val lng: Double)
@JsonClass(generateAdapter = true) data class OkResponse(val ok: Boolean? = null, val deleted: Boolean? = null)

// ---- Wallet
@JsonClass(generateAdapter = true) data class WalletDto(val balancePaise: Long, val heldPaise: Long, val availablePaise: Long, val currency: String)
@JsonClass(generateAdapter = true) data class LedgerEntryDto(
    val id: String,
    val type: String,
    val amountPaise: Long,
    val balanceAfterPaise: Long,
    val heldAfterPaise: Long,
    val reference: String,
    val paymentId: String?,
    val rideId: String?,
    val description: String,
    val createdAt: Long,
)
@JsonClass(generateAdapter = true) data class LedgerResponse(val transactions: List<LedgerEntryDto>)

// ---- Payments
@JsonClass(generateAdapter = true) data class CreateOrderBody(val amountPaise: Long)
@JsonClass(generateAdapter = true) data class PaymentDto(
    val paymentId: String,
    val provider: String,
    val providerOrderId: String,
    val amountPaise: Long,
    val refundedPaise: Long = 0,
    val currency: String,
    val status: String,
    val failureReason: String? = null,
    val publicKey: String? = null,
)
@JsonClass(generateAdapter = true) data class VerifyPaymentBody(
    val paymentId: String,
    val outcome: String,
    val providerPaymentId: String? = null,
    val signature: String? = null,
    val reason: String? = null,
)
@JsonClass(generateAdapter = true) data class VerifyPaymentResponse(val payment: PaymentDto, val wallet: WalletDto)
@JsonClass(generateAdapter = true) data class TestCheckoutBody(val paymentId: String, val outcome: String)
@JsonClass(generateAdapter = true) data class TestCheckoutResponse(val outcome: String, val providerPaymentId: String? = null, val signature: String? = null)

// ---- Locations
@JsonClass(generateAdapter = true) data class LatLngDto(val lat: Double, val lng: Double)
@JsonClass(generateAdapter = true) data class PlaceDto(
    val name: String, val subtitle: String = "", val lat: Double, val lng: Double, val category: String? = null, val source: String? = null,
)
@JsonClass(generateAdapter = true) data class GeocodeBody(val query: String)
@JsonClass(generateAdapter = true) data class GeocodeResponse(val results: List<PlaceDto>)
@JsonClass(generateAdapter = true) data class RouteBody(val origin: LatLngDto, val destination: LatLngDto)
@JsonClass(generateAdapter = true) data class RouteDto(val distanceMeters: Long, val durationMinutes: Long, val polyline: List<LatLngDto>, val source: String)
@JsonClass(generateAdapter = true) data class RouteInfoDto(val polyline: List<LatLngDto>, val source: String)

// ---- Rides
@JsonClass(generateAdapter = true) data class QuoteBody(val vehicleId: String, val pickup: PlaceDto, val dropoff: PlaceDto)
@JsonClass(generateAdapter = true) data class FareDto(
    val vehicleId: String,
    val distanceMeters: Long,
    val durationMinutes: Long,
    val baseFarePaise: Long,
    val perKmRatePaise: Long,
    val perMinRatePaise: Long,
    val distanceChargePaise: Long,
    val durationChargePaise: Long,
    val categoryMultiplierBp: Long,
    val luxuryMultiplierBp: Long,
    val engineCcFactorBp: Long,
    val transitChargePaise: Long,
    val subtotalPaise: Long,
    val platformSafetyFeePaise: Long,
    val taxablePaise: Long,
    val gstPaise: Long,
    val totalPaise: Long,
    val pilotEarningPaise: Long,
    val platformCommissionPaise: Long,
)
@JsonClass(generateAdapter = true) data class QuoteDto(
    val quoteId: String,
    val vehicleId: String,
    val pickup: PlaceDto,
    val dropoff: PlaceDto,
    val distanceMeters: Long,
    val durationMinutes: Long,
    val route: RouteInfoDto,
    val fare: FareDto,
    val expiresAt: Long,
)
@JsonClass(generateAdapter = true) data class RequestRideBody(val quoteId: String, val expectedTotalPaise: Long)
@JsonClass(generateAdapter = true) data class PilotLocationDto(val lat: Double, val lng: Double, val updatedAt: Long? = null)
@JsonClass(generateAdapter = true) data class RidePilotDto(
    val pilotId: String,
    val name: String,
    val rating: Double,
    val totalTrips: Int,
    val vehicleModel: String,
    val vehicleColor: String,
    val licensePlate: String,
    val avatarInitials: String,
    val location: PilotLocationDto?,
)
@JsonClass(generateAdapter = true) data class RatingDto(val stars: Int, val tags: String?)
@JsonClass(generateAdapter = true) data class RideDto(
    val rideId: String,
    val status: String,
    val version: Long,
    val vehicleId: String,
    val pickup: PlaceDto,
    val dropoff: PlaceDto,
    val distanceMeters: Long,
    val durationMinutes: Long,
    val route: RouteInfoDto,
    val fare: FareDto,
    val fareTotalPaise: Long,
    val chargedPaise: Long,
    val startOtp: String?,
    val pilot: RidePilotDto?,
    val progressFraction: Double,
    val cancelReason: String? = null,
    val rating: RatingDto? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
)
@JsonClass(generateAdapter = true) data class RideListResponse(val rides: List<RideDto>)
@JsonClass(generateAdapter = true) data class ActiveRideResponse(val ride: RideDto?)
@JsonClass(generateAdapter = true) data class CancelRideBody(val reason: String?)
@JsonClass(generateAdapter = true) data class RatingBody(val stars: Int, val tags: List<String>)
@JsonClass(generateAdapter = true) data class MessageDto(val id: String, val senderRole: String, val body: String, val createdAt: Long)
@JsonClass(generateAdapter = true) data class MessageListResponse(val messages: List<MessageDto>)
@JsonClass(generateAdapter = true) data class NewMessageBody(val body: String)
@JsonClass(generateAdapter = true) data class VerifyStartOtpBody(val otp: String)

// ---- Pilots
@JsonClass(generateAdapter = true) data class NearbyPilotDto(
    val pilotId: String, val vehicleIds: List<String>, val location: LatLngDto, val distanceMeters: Long, val etaMinutes: Int,
)
@JsonClass(generateAdapter = true) data class NearbyPilotsResponse(val pilots: List<NearbyPilotDto>)
@JsonClass(generateAdapter = true) data class AvailabilityBody(val status: String)

// ---- Safety / config
@JsonClass(generateAdapter = true) data class SosBody(val lat: Double?, val lng: Double?, val rideId: String?)
@JsonClass(generateAdapter = true) data class ServerConfigDto(
    val authProvider: String?,
    val paymentProvider: String?,
    val mapsProvider: String?,
    val sosAvailable: Boolean,
    val maskedCallAvailable: Boolean,
    val testMode: Boolean,
)
