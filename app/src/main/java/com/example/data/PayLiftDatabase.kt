package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [WalletTransaction::class, RideRecord::class, UserProfile::class],
    version = 1,
    exportSchema = false
)
abstract class PayLiftDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao
    abstract fun rideDao(): RideDao
    abstract fun userProfileDao(): UserProfileDao

    companion object {
        @Volatile
        private var INSTANCE: PayLiftDatabase? = null

        fun getDatabase(context: Context): PayLiftDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PayLiftDatabase::class.java,
                    "paylift_master.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
