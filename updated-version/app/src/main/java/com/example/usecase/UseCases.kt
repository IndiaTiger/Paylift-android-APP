package com.example.usecase

import com.example.core.AppError
import com.example.core.Outcome
import com.example.domain.LocationPoint
import com.example.domain.LocationValidation
import com.example.domain.Paise
import com.example.domain.RideStatus
import com.example.domain.TripValidation
import com.example.repository.LocationRepository
import com.example.repository.PaymentRepository
import com.example.repository.PilotRepository
import com.example.repository.ProfileRepository
import com.example.repository.RideRepository
import com.example.repository.SafetyRepository
import com.example.repository.SessionRepository
import com.example.repository.TopUpResult
import com.example.services.OtpChallenge
import com.example.services.ProfilePatch
import com.example.services.Ride
import com.example.services.RideQuote
import com.example.services.UserAccount

private fun invalid(code: String, message: String) = Outcome.Failure(AppError.Api(400, code, message))

// ------------------------------------------------------------------------------ Auth
class RequestOtpUseCase(private val session: SessionRepository) {
    suspend operator fun invoke(rawPhone: String): Outcome<OtpChallenge> {
        val phone = normalizeIndianPhone(rawPhone) ?: return invalid("INVALID_PHONE", "Enter a valid mobile number")
        return session.requestOtp(phone)
    }

    companion object {
        /** Accepts "98970 12345", "+91 98970-12345", "919897012345" -> "+919897012345". */
        fun normalizeIndianPhone(raw: String): String? {
            val digits = raw.filter { it.isDigit() || it == '+' }
            val e164 = when {
                digits.startsWith("+") -> digits
                digits.length == 10 -> "+91$digits"
                digits.length == 12 && digits.startsWith("91") -> "+$digits"
                else -> return null
            }
            return e164.takeIf { Regex("^\\+[1-9]\\d{7,14}$").matches(it) }
        }
    }
}

class VerifyOtpUseCase(private val session: SessionRepository, private val profile: ProfileRepository) {
    suspend operator fun invoke(challenge: OtpChallenge, otp: String): Outcome<UserAccount> {
        if (!Regex("^\\d{6}$").matches(otp.trim())) return invalid("INVALID_OTP", "Enter the 6-digit code")
        val r = session.verifyOtp(challenge, otp.trim())
        if (r is Outcome.Success) {
            profile.refreshProfile()
            profile.refreshWallet()
        }
        return r
    }
}

// ------------------------------------------------------------------------------ Rides
class GetFareQuoteUseCase(private val rides: RideRepository) {
    suspend operator fun invoke(vehicleId: String, pickup: LocationPoint, dropoff: LocationPoint): Outcome<RideQuote> =
        when (val v = LocationValidation.validateTrip(pickup, dropoff)) {
            is TripValidation.Invalid -> invalid("INVALID_TRIP", v.reason)
            TripValidation.Valid -> rides.quote(vehicleId, pickup, dropoff)
        }
}

class RequestRideUseCase(private val rides: RideRepository, private val now: () -> Long = System::currentTimeMillis) {
    /**
     * [availablePaise] is the last server-reported available balance; it only short-circuits an
     * obviously failing request. The server performs the authoritative, atomic funds check.
     */
    suspend operator fun invoke(quote: RideQuote?, availablePaise: Long): Outcome<Ride> {
        if (quote == null) return invalid("NO_QUOTE", "Fare is still loading")
        if (quote.expiresAt <= now()) return invalid("QUOTE_EXPIRED", "Fare expired, refreshing…")
        if (!quote.fare.isConsistent()) return invalid("BAD_QUOTE", "Fare could not be verified")
        if (availablePaise < quote.fare.totalPaise) {
            return Outcome.Failure(AppError.Api(409, "INSUFFICIENT_FUNDS", "Insufficient wallet balance"))
        }
        return rides.request(quote)
    }
}

class CancelRideUseCase(private val rides: RideRepository) {
    suspend operator fun invoke(rideId: String, status: RideStatus, reason: String?): Outcome<Ride> {
        if (!status.riderCanCancel) return invalid("NOT_CANCELLABLE", "This ride can no longer be cancelled")
        return rides.cancel(rideId, reason)
    }
}

class RateRideUseCase(private val rides: RideRepository) {
    suspend operator fun invoke(rideId: String, stars: Int, tags: String): Outcome<Ride> {
        if (stars !in 1..5) return invalid("INVALID_RATING", "Choose 1 to 5 stars")
        return rides.rate(rideId, stars, tags.split(",").map { it.trim() }.filter { it.isNotEmpty() })
    }
}

/** Startup / reconnect: resume an interrupted ride request, the active ride and unconfirmed payments. */
class RecoverStateUseCase(
    private val rides: RideRepository,
    private val payments: PaymentRepository,
    private val profile: ProfileRepository,
) {
    suspend operator fun invoke(): Outcome<Ride?> {
        profile.refreshProfile()
        payments.reconcilePending()
        profile.refreshWallet()
        rides.refreshHistory()
        return rides.recover()
    }
}

// ------------------------------------------------------------------------------ Wallet
class TopUpWalletUseCase(private val payments: PaymentRepository) {
    suspend operator fun invoke(amountPaise: Long, idempotencyKey: String): Outcome<TopUpResult> {
        if (amountPaise < MIN_PAISE || amountPaise > MAX_PAISE) {
            return invalid("INVALID_AMOUNT", "Enter an amount between ₹${MIN_PAISE / 100} and ₹${MAX_PAISE / 100}")
        }
        if (!payments.isAvailable) {
            return Outcome.Failure(AppError.NotConfigured("PAYMENT_PROVIDER", "Online payments are not available yet in this build"))
        }
        return payments.topUp(amountPaise, idempotencyKey)
    }

    companion object {
        const val MIN_PAISE = 100L // must match backend MIN_TOPUP_PAISE
        const val MAX_PAISE = 1_000_000L // must match backend MAX_TOPUP_PAISE

        fun parseAmount(input: String): Long? = Paise.parseRupees(input)?.value
    }
}

// ------------------------------------------------------------------------------ Profile / location / safety
class UpdateProfileUseCase(private val profile: ProfileRepository) {
    suspend operator fun invoke(patch: ProfilePatch): Outcome<UserAccount> {
        patch.emergencyContactPhone?.let {
            if (it.isNotEmpty() && !Regex("^\\+?[0-9 ]{8,20}$").matches(it)) return invalid("INVALID_PHONE", "Emergency phone number is invalid")
        }
        return profile.update(patch)
    }
}

class SearchPlacesUseCase(private val locations: LocationRepository) {
    suspend operator fun invoke(query: String) = locations.search(query.trim().take(120))
}

class NearbyEtaUseCase(private val pilots: PilotRepository) {
    suspend operator fun invoke(pickup: LocationPoint): Outcome<Map<String, Int>> =
        if (!pickup.isSelected) Outcome.Success(emptyMap()) else pilots.etaByVehicle(pickup.latitude, pickup.longitude)
}

class TriggerSosUseCase(private val safety: SafetyRepository) {
    suspend operator fun invoke(lat: Double?, lng: Double?, rideId: String?) = safety.triggerSos(lat, lng, rideId)
}
