package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CacheDao {
    // ---- Wallet / ledger (server snapshots only)
    @Query("SELECT * FROM cached_wallet LIMIT 1")
    fun wallet(): Flow<CachedWallet?>

    @Query("SELECT * FROM cached_ledger_entries ORDER BY createdAt DESC, id DESC")
    fun ledger(): Flow<List<CachedLedgerEntry>>

    @Upsert suspend fun upsertWallet(wallet: CachedWallet)
    @Query("DELETE FROM cached_ledger_entries") suspend fun clearLedger()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLedger(entries: List<CachedLedgerEntry>)

    /** Replaces the cached ledger atomically with the server's latest page. */
    @Transaction
    suspend fun replaceLedger(entries: List<CachedLedgerEntry>) {
        clearLedger()
        insertLedger(entries)
    }

    // ---- Profile
    @Query("SELECT * FROM cached_profile LIMIT 1") fun profile(): Flow<CachedProfile?>
    @Upsert suspend fun upsertProfile(profile: CachedProfile)

    // ---- Rides
    @Query("SELECT * FROM cached_rides WHERE status IN ('COMPLETED','CANCELLED','FAILED','NO_PILOT_FOUND') ORDER BY createdAt DESC")
    fun rideHistory(): Flow<List<CachedRide>>

    @Query("SELECT * FROM cached_rides WHERE rideId = :rideId") suspend fun ride(rideId: String): CachedRide?

    /** Only accepts a snapshot that is not older than what is cached (server version counter). */
    @Transaction
    suspend fun upsertRideIfNewer(ride: CachedRide) {
        val existing = ride(ride.rideId)
        if (existing == null || ride.version >= existing.version) upsertRide(ride)
    }

    @Upsert suspend fun upsertRide(ride: CachedRide)
    @Query("DELETE FROM cached_rides") suspend fun clearRides()

    @Transaction
    suspend fun replaceRides(rides: List<CachedRide>) {
        clearRides()
        rides.forEach { upsertRide(it) }
    }

    // ---- Active ride recovery
    @Query("SELECT * FROM active_ride_pointer WHERE slot = 0") suspend fun activeRide(): ActiveRidePointer?
    @Upsert suspend fun setActiveRide(pointer: ActiveRidePointer)
    @Query("DELETE FROM active_ride_pointer") suspend fun clearActiveRide()

    @Query("SELECT * FROM pending_ride_requests ORDER BY createdAt LIMIT 1") suspend fun pendingRideRequest(): PendingRideRequest?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertPendingRideRequest(request: PendingRideRequest)
    @Query("DELETE FROM pending_ride_requests WHERE idempotencyKey = :key") suspend fun deletePendingRideRequest(key: String)

    // ---- Payment recovery
    @Query("SELECT * FROM pending_payments ORDER BY createdAt") suspend fun pendingPayments(): List<PendingPayment>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPendingPayment(payment: PendingPayment)
    @Query("DELETE FROM pending_payments WHERE paymentId = :paymentId") suspend fun deletePendingPayment(paymentId: String)

    // ---- Sign-out / account deletion
    @Query("DELETE FROM cached_wallet") suspend fun clearWallet()
    @Query("DELETE FROM cached_profile") suspend fun clearProfile()
    @Query("DELETE FROM pending_ride_requests") suspend fun clearPendingRideRequests()
    @Query("DELETE FROM pending_payments") suspend fun clearPendingPayments()

    @Transaction
    suspend fun clearAll() {
        clearLedger(); clearWallet(); clearProfile(); clearRides(); clearActiveRide(); clearPendingRideRequests(); clearPendingPayments()
    }
}
