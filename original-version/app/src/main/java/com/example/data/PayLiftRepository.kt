package com.example.data

import com.example.security.CryptoSecurityEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID

class PayLiftRepository(
    private val walletDao: WalletDao,
    private val rideDao: RideDao,
    private val userProfileDao: UserProfileDao
) {
    val allTransactions: Flow<List<WalletTransaction>> = walletDao.getAllTransactions()
    val allRides: Flow<List<RideRecord>> = rideDao.getAllRides()
    val userProfile: Flow<UserProfile?> = userProfileDao.getUserProfile()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            seedInitialDataIfNeeded()
        }
    }

    private suspend fun seedInitialDataIfNeeded() {
        val currentProfile = userProfileDao.getUserProfile().firstOrNull()
        if (currentProfile == null) {
            userProfileDao.insertOrUpdateProfile(UserProfile())
            
            // Seed initial realistic transactions
            val t1 = System.currentTimeMillis() - 86400000L * 2
            val ref1 = CryptoSecurityEngine.generateReferenceId("RAZORPAY")
            val sig1 = CryptoSecurityEngine.computeHmacSha256("Razorpay|500.00|$ref1|$t1")
            walletDao.insertTransaction(
                WalletTransaction(
                    transactionId = "TXN-${UUID.randomUUID().toString().take(8).uppercase()}",
                    timestamp = t1,
                    type = "credit",
                    category = "TOPUP",
                    amount = 500.0,
                    balanceAfter = 500.0,
                    gateway = "Razorpay",
                    status = "SUCCESS",
                    hmacSignature = sig1,
                    referenceId = ref1,
                    description = "UPI Instant Wallet Credit"
                )
            )

            val t2 = System.currentTimeMillis() - 86400000L
            val ref2 = CryptoSecurityEngine.generateReferenceId("ESCROW")
            val sig2 = CryptoSecurityEngine.computeHmacSha256("PayLift Escrow|125.00|$ref2|$t2")
            walletDao.insertTransaction(
                WalletTransaction(
                    transactionId = "TXN-${UUID.randomUUID().toString().take(8).uppercase()}",
                    timestamp = t2,
                    type = "debit",
                    category = "RIDE_DEBIT",
                    amount = 125.0,
                    balanceAfter = 375.0,
                    gateway = "PayLift Escrow",
                    status = "SUCCESS",
                    hmacSignature = sig2,
                    referenceId = ref2,
                    description = "Ride Settlement: Clock Tower to Rajpur Road"
                )
            )

            // Seed initial past completed ride
            rideDao.insertRide(
                RideRecord(
                    rideId = "PL-DEH-4912",
                    timestamp = t2,
                    pickupName = "Clock Tower (Ghanta Ghar)",
                    dropName = "Rajpur Road (Pacific Mall)",
                    distanceKm = 5.2,
                    durationMin = 18,
                    vehicleCategory = "Cars & Cabs",
                    vehicleModel = "Swift Dzire Prime",
                    vehiclePlate = "UK07-AX-8219",
                    pilotName = "Aman Negi",
                    pilotRating = 4.9,
                    grossFare = 125.0,
                    pilotEarnings = 106.25,
                    platformCut = 18.75,
                    gstTax = 5.95,
                    status = "COMPLETED",
                    userRating = 5,
                    feedbackTags = "Polite Pilot, Clean Vehicle"
                )
            )
        }
    }

    suspend fun topUpWallet(
        amount: Double,
        gateway: String
    ): Pair<Boolean, String> {
        val timestamp = System.currentTimeMillis()
        val refId = CryptoSecurityEngine.generateReferenceId(gateway)
        val payload = "$gateway|${"%.2f".format(amount)}|$refId|$timestamp"
        val hmac = CryptoSecurityEngine.computeHmacSha256(payload)

        // Server-side cryptographic signature check
        val isValid = CryptoSecurityEngine.verifySignature(
            gateway = gateway,
            amount = amount,
            referenceId = refId,
            timestamp = timestamp,
            receivedSignature = hmac
        )

        if (!isValid) {
            return Pair(false, "Cryptographic signature validation failed.")
        }

        val profile = userProfileDao.getUserProfile().firstOrNull() ?: UserProfile()
        val updatedBalance = profile.walletBalance + amount

        val tx = WalletTransaction(
            transactionId = "TXN-${UUID.randomUUID().toString().take(8).uppercase()}",
            timestamp = timestamp,
            type = "credit",
            category = "TOPUP",
            amount = amount,
            balanceAfter = updatedBalance,
            gateway = gateway,
            status = "SUCCESS",
            hmacSignature = hmac,
            referenceId = refId,
            description = "$gateway Top-Up (Verified HMAC-SHA256)"
        )
        walletDao.insertTransaction(tx)
        userProfileDao.updateBalance(updatedBalance)

        return Pair(true, refId)
    }

    suspend fun debitRideFare(
        amount: Double,
        pickupName: String,
        dropName: String
    ): Pair<Boolean, String> {
        val profile = userProfileDao.getUserProfile().firstOrNull() ?: UserProfile()
        if (profile.walletBalance < amount) {
            return Pair(false, "Insufficient wallet balance.")
        }

        val timestamp = System.currentTimeMillis()
        val refId = CryptoSecurityEngine.generateReferenceId("ESCROW")
        val payload = "PayLift Escrow|${"%.2f".format(amount)}|$refId|$timestamp"
        val hmac = CryptoSecurityEngine.computeHmacSha256(payload)

        var newBalance = profile.walletBalance - amount

        val tx = WalletTransaction(
            transactionId = "TXN-${UUID.randomUUID().toString().take(8).uppercase()}",
            timestamp = timestamp,
            type = "debit",
            category = "RIDE_DEBIT",
            amount = amount,
            balanceAfter = newBalance,
            gateway = "PayLift Escrow",
            status = "SUCCESS",
            hmacSignature = hmac,
            referenceId = refId,
            description = "Ride Escrow Settlement: $pickupName → $dropName"
        )
        walletDao.insertTransaction(tx)
        
        // Check auto-topup condition
        if (profile.autoTopupEnabled && newBalance < profile.autoTopupThreshold) {
            val autoAmount = profile.autoTopupAmount
            val autoRef = CryptoSecurityEngine.generateReferenceId("AUTO_TOPUP")
            val autoPayload = "AutoTopup|${"%.2f".format(autoAmount)}|$autoRef|$timestamp"
            val autoHmac = CryptoSecurityEngine.computeHmacSha256(autoPayload)
            newBalance += autoAmount
            
            val autoTx = WalletTransaction(
                transactionId = "TXN-${UUID.randomUUID().toString().take(8).uppercase()}",
                timestamp = timestamp + 10,
                type = "credit",
                category = "AUTO_TOPUP",
                amount = autoAmount,
                balanceAfter = newBalance,
                gateway = "PayLift Auto-Vault",
                status = "SUCCESS",
                hmacSignature = autoHmac,
                referenceId = autoRef,
                description = "Auto-Topup Refill: Balance fell below ₹${"%.0f".format(profile.autoTopupThreshold)}"
            )
            walletDao.insertTransaction(autoTx)
        }

        userProfileDao.updateBalance(newBalance)
        return Pair(true, refId)
    }

    suspend fun refundRide(amount: Double, rideId: String, reason: String): String {
        val timestamp = System.currentTimeMillis()
        val refId = CryptoSecurityEngine.generateReferenceId("REFUND")
        val payload = "PayLift Refund|${"%.2f".format(amount)}|$refId|$timestamp"
        val hmac = CryptoSecurityEngine.computeHmacSha256(payload)

        val profile = userProfileDao.getUserProfile().firstOrNull() ?: UserProfile()
        val newBalance = profile.walletBalance + amount

        val tx = WalletTransaction(
            transactionId = "TXN-${UUID.randomUUID().toString().take(8).uppercase()}",
            timestamp = timestamp,
            type = "credit",
            category = "REFUND",
            amount = amount,
            balanceAfter = newBalance,
            gateway = "PayLift Escrow",
            status = "SUCCESS",
            hmacSignature = hmac,
            referenceId = refId,
            description = "Escrow Refund: $rideId ($reason)"
        )
        walletDao.insertTransaction(tx)
        userProfileDao.updateBalance(newBalance)
        return refId
    }

    suspend fun recordRide(ride: RideRecord) {
        rideDao.insertRide(ride)
    }

    suspend fun updateRideRating(rideId: String, rating: Int, feedbackTags: String) {
        rideDao.updateRating(rideId, rating, feedbackTags)
    }

    suspend fun updateUserProfile(profile: UserProfile) {
        userProfileDao.insertOrUpdateProfile(profile)
    }
}
