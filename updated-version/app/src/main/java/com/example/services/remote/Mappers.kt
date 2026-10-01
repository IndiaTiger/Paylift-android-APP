package com.example.services.remote

import com.example.api.dto.AddressDto
import com.example.api.dto.FareDto
import com.example.api.dto.LedgerEntryDto
import com.example.api.dto.PaymentDto
import com.example.api.dto.PlaceDto
import com.example.api.dto.RideDto
import com.example.api.dto.UserDto
import com.example.api.dto.WalletDto
import com.example.domain.FareQuote
import com.example.domain.LocationPoint
import com.example.domain.LocationSource
import com.example.domain.RideStatus
import com.example.services.LedgerEntry
import com.example.services.LedgerType
import com.example.services.PaymentOrder
import com.example.services.PaymentStatus
import com.example.services.Ride
import com.example.services.RidePilot
import com.example.services.SavedAddress
import com.example.services.UserAccount
import com.example.services.WalletBalance

internal fun UserDto.toDomain() = UserAccount(
    userId = userId, phone = phone.orEmpty(), name = name, email = email,
    emergencyContactName = emergencyContactName, emergencyContactPhone = emergencyContactPhone,
    emergencyRelationship = emergencyRelationship, autoDialSos = autoDialSos,
)

internal fun WalletDto.toDomain() = WalletBalance(balancePaise, heldPaise, availablePaise, currency)

internal fun LedgerEntryDto.toDomain() = LedgerEntry(
    id = id, type = LedgerType.entries.firstOrNull { it.name == type } ?: LedgerType.UNKNOWN, amountPaise = amountPaise,
    balanceAfterPaise = balanceAfterPaise, heldAfterPaise = heldAfterPaise, reference = reference,
    paymentId = paymentId, rideId = rideId, description = description, createdAt = createdAt,
)

internal fun PaymentDto.toDomain() = PaymentOrder(paymentId, provider, providerOrderId, amountPaise, PaymentStatus.parse(status), publicKey)

internal fun PlaceDto.toDomain(defaultSource: LocationSource = LocationSource.SEARCH) = LocationPoint(
    name = name, subtitle = subtitle, latitude = lat, longitude = lng, category = category ?: "Place",
    source = if (source?.startsWith("dev") == true) LocationSource.DEV_DATA else defaultSource,
)

internal fun LocationPoint.toDto() = PlaceDto(name = name, subtitle = subtitle, lat = latitude, lng = longitude)

internal fun AddressDto.toDomain() = SavedAddress(
    addressId, label, LocationPoint(name, subtitle, lat, lng, label, LocationSource.SAVED),
)

internal fun FareDto.toDomain() = FareQuote(
    vehicleId, distanceMeters, durationMinutes, baseFarePaise, perKmRatePaise, perMinRatePaise, distanceChargePaise,
    durationChargePaise, categoryMultiplierBp, luxuryMultiplierBp, engineCcFactorBp, transitChargePaise, subtotalPaise,
    platformSafetyFeePaise, taxablePaise, gstPaise, totalPaise, pilotEarningPaise, platformCommissionPaise,
)

internal fun RideDto.toDomain() = Ride(
    rideId = rideId,
    status = RideStatus.parse(status),
    version = version,
    vehicleId = vehicleId,
    pickup = pickup.toDomain(),
    dropoff = dropoff.toDomain(),
    distanceMeters = distanceMeters,
    durationMinutes = durationMinutes,
    polyline = route.polyline.map { it.lat to it.lng },
    fare = fare.toDomain(),
    chargedPaise = chargedPaise,
    startOtp = startOtp,
    pilot = pilot?.let {
        RidePilot(it.pilotId, it.name, it.rating, it.totalTrips, it.vehicleModel, it.vehicleColor, it.licensePlate,
            it.avatarInitials, it.location?.let { l -> l.lat to l.lng })
    },
    progressFraction = progressFraction.coerceIn(0.0, 1.0),
    ratingStars = rating?.stars,
    ratingTags = rating?.tags,
    createdAt = createdAt,
)
