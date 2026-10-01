package com.example.data

// Display models consumed by the Compose screens. They are derived from server data (via the
// repositories) and carry display conversions only; nothing here is used for money arithmetic.

data class UserProfile(
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",
    val emergencyRelationship: String = "",
    /** Available balance (balance - held) in rupees, display only. Authoritative value: [walletAvailablePaise]. */
    val walletBalance: Double = 0.0,
    val walletAvailablePaise: Long = 0,
    val walletHeldPaise: Long = 0,
    /** No savings programme exists; always 0 until the backend reports one. */
    val lifetimeSavings: Double = 0.0,
    /** Auto top-up needs a recurring payment mandate from the gateway; not available yet. */
    val autoTopupAvailable: Boolean = false,
    val autoTopupEnabled: Boolean = false,
    val autoTopupThreshold: Double = 150.0,
    val autoTopupAmount: Double = 500.0,
    val autoDialSos: Boolean = false,
    /** True once the wallet was loaded from the server at least once. */
    val walletLoaded: Boolean = false,
)

data class WalletTransaction(
    val transactionId: String,
    val timestamp: Long,
    val type: String, // "credit" | "debit" | "hold" | "release"
    val category: String, // "TOPUP" | "RIDE_DEBIT" | "REFUND" | "HOLD"
    val amount: Double,
    val balanceAfter: Double,
    val gateway: String,
    val status: String,
    val referenceId: String,
    val description: String,
)

data class RideRecord(
    val rideId: String,
    val timestamp: Long,
    val pickupName: String,
    val dropName: String,
    val distanceKm: Double,
    val durationMin: Int,
    val vehicleCategory: String,
    val vehicleModel: String,
    val vehiclePlate: String,
    val pilotName: String,
    val pilotRating: Double,
    val grossFare: Double,
    val pilotEarnings: Double,
    val platformCut: Double,
    val gstTax: Double,
    val status: String, // "COMPLETED", "CANCELLED", ...
    val userRating: Int = 0,
    val feedbackTags: String = "",
    /** Amount actually charged (== grossFare for completed rides; fee or 0 otherwise). */
    val chargedAmount: Double = 0.0,
)
