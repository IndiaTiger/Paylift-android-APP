package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wallet_transactions")
data class WalletTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String, // "credit", "debit", "TOP_UP", "RIDE_DEBIT", "REFUND", "AUTO_TOPUP"
    val category: String = "ALL", // "TOPUP", "RIDE_DEBIT", "REFUND", "AUTO_TOPUP"
    val amount: Double,
    val balanceAfter: Double = 0.0,
    val gateway: String, // "Razorpay", "Cashfree", "Stripe", "Mock Sandbox", "PayLift Escrow"
    val status: String, // "SUCCESS", "PENDING", "FAILED"
    val hmacSignature: String,
    val referenceId: String,
    val description: String
)

@Entity(tableName = "ride_records")
data class RideRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: String,
    val timestamp: Long = System.currentTimeMillis(),
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
    val status: String, // "COMPLETED", "CANCELLED"
    val userRating: Int = 0,
    val feedbackTags: String = ""
)

@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Int = 1,
    val name: String = "Mohit Tyagi",
    val email: String = "mtyagi45678909@gmail.com",
    val phone: String = "+91 98970 12345",
    val emergencyContactName: String = "Pooja Sharma",
    val emergencyContactPhone: String = "+91 98112 34567",
    val emergencyRelationship: String = "Sister / Family",
    val walletBalance: Double = 650.00,
    val lifetimeSavings: Double = 340.00,
    val autoTopupEnabled: Boolean = true,
    val autoTopupThreshold: Double = 150.00,
    val autoTopupAmount: Double = 500.00,
    val autoDialSos: Boolean = true,
    val defaultPaymentMethod: String = "PayLift Wallet (Escrow)",
    val mapStyle: String = "Classic Blue"
)
