package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Room v2: a CACHE of server state for offline display and crash recovery.
// Nothing here is authoritative for money; balances are always refreshed from GET /wallet and
// ledger rows are only ever written from server responses.

@Entity(tableName = "cached_ledger_entries", indices = [Index(value = ["reference"], unique = true), Index("createdAt")])
data class CachedLedgerEntry(
    @PrimaryKey val id: String,
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

@Entity(tableName = "cached_wallet")
data class CachedWallet(
    @PrimaryKey val userId: String,
    val balancePaise: Long,
    val heldPaise: Long,
    val availablePaise: Long,
    val fetchedAt: Long,
)

@Entity(tableName = "cached_profile")
data class CachedProfile(
    @PrimaryKey val userId: String,
    val phone: String,
    val name: String,
    val email: String,
    val emergencyContactName: String,
    val emergencyContactPhone: String,
    val emergencyRelationship: String,
    val autoDialSos: Boolean,
    val fetchedAt: Long,
)

@Entity(tableName = "cached_rides", indices = [Index("createdAt"), Index("status")])
data class CachedRide(
    @PrimaryKey val rideId: String,
    val status: String,
    val version: Long,
    val vehicleId: String,
    val pickupName: String,
    val dropName: String,
    val distanceMeters: Long,
    val durationMinutes: Long,
    val fareTotalPaise: Long,
    val chargedPaise: Long,
    val gstPaise: Long,
    val pilotEarningPaise: Long,
    val platformCommissionPaise: Long,
    val pilotName: String?,
    val pilotRating: Double?,
    val vehicleModel: String?,
    val vehiclePlate: String?,
    val ratingStars: Int?,
    val ratingTags: String?,
    val createdAt: Long,
)

/**
 * Pointer to the ride the rider is currently on, persisted so the ride is recovered after
 * backgrounding, process death, restart or reconnect (the server is then asked for its state).
 */
@Entity(tableName = "active_ride_pointer")
data class ActiveRidePointer(
    @PrimaryKey val slot: Int = 0,
    val rideId: String,
    val lastKnownStatus: String,
    val lastKnownVersion: Long,
    val updatedAt: Long,
)

/**
 * A ride request that was sent (or was about to be) when the app died. Re-sending it with the
 * same idempotency key returns the same ride instead of creating (and charging) a second one.
 */
@Entity(tableName = "pending_ride_requests")
data class PendingRideRequest(
    @PrimaryKey val idempotencyKey: String,
    val quoteId: String,
    val expectedTotalPaise: Long,
    val createdAt: Long,
)

/**
 * A payment order whose outcome the app has not yet seen confirmed by the server. On startup
 * these are re-checked with GET /payments/{id}; the wallet is never credited locally.
 */
@Entity(tableName = "pending_payments", indices = [Index(value = ["idempotencyKey"], unique = true)])
data class PendingPayment(
    @PrimaryKey val paymentId: String,
    val idempotencyKey: String,
    val amountPaise: Long,
    val createdAt: Long,
)
