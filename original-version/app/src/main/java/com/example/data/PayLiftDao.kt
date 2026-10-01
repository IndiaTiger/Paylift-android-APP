package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallet_transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<WalletTransaction>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(tx: WalletTransaction): Long

    @Query("SELECT * FROM wallet_transactions WHERE type = :type ORDER BY timestamp DESC")
    fun getTransactionsByType(type: String): Flow<List<WalletTransaction>>
}

@Dao
interface RideDao {
    @Query("SELECT * FROM ride_records ORDER BY timestamp DESC")
    fun getAllRides(): Flow<List<RideRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRide(ride: RideRecord): Long

    @Query("UPDATE ride_records SET userRating = :rating, feedbackTags = :tags WHERE rideId = :rideId")
    suspend fun updateRating(rideId: String, rating: Int, tags: String)
}

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = 1 LIMIT 1")
    fun getUserProfile(): Flow<UserProfile?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateProfile(profile: UserProfile)

    @Query("UPDATE user_profile SET walletBalance = :balance WHERE id = 1")
    suspend fun updateBalance(balance: Double)
}
