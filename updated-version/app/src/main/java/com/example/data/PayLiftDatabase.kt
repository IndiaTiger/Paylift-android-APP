package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CachedLedgerEntry::class, CachedWallet::class, CachedProfile::class, CachedRide::class,
        ActiveRidePointer::class, PendingRideRequest::class, PendingPayment::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class PayLiftDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao

    companion object {
        const val NAME = "paylift_master.db"

        /**
         * v1 (prototype) -> v2 (server cache).
         *
         * v1 stored a client-authoritative wallet (locally invented balances, locally generated
         * "HMAC" signatures, seeded demo transactions). That data is not real money and must not
         * be shown as a balance. It is NOT dropped: the three v1 tables are renamed to legacy_*
         * so nothing is destroyed (support can still inspect them), and the new cache tables are
         * created empty and filled from the server after sign-in.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `wallet_transactions` RENAME TO `legacy_v1_wallet_transactions`")
                db.execSQL("ALTER TABLE `ride_records` RENAME TO `legacy_v1_ride_records`")
                db.execSQL("ALTER TABLE `user_profile` RENAME TO `legacy_v1_user_profile`")
                db.execSQL("CREATE TABLE IF NOT EXISTS `cached_ledger_entries` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `amountPaise` INTEGER NOT NULL, `balanceAfterPaise` INTEGER NOT NULL, `heldAfterPaise` INTEGER NOT NULL, `reference` TEXT NOT NULL, `paymentId` TEXT, `rideId` TEXT, `description` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cached_ledger_entries_reference` ON `cached_ledger_entries` (`reference`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_ledger_entries_createdAt` ON `cached_ledger_entries` (`createdAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `cached_wallet` (`userId` TEXT NOT NULL, `balancePaise` INTEGER NOT NULL, `heldPaise` INTEGER NOT NULL, `availablePaise` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `cached_profile` (`userId` TEXT NOT NULL, `phone` TEXT NOT NULL, `name` TEXT NOT NULL, `email` TEXT NOT NULL, `emergencyContactName` TEXT NOT NULL, `emergencyContactPhone` TEXT NOT NULL, `emergencyRelationship` TEXT NOT NULL, `autoDialSos` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `cached_rides` (`rideId` TEXT NOT NULL, `status` TEXT NOT NULL, `version` INTEGER NOT NULL, `vehicleId` TEXT NOT NULL, `pickupName` TEXT NOT NULL, `dropName` TEXT NOT NULL, `distanceMeters` INTEGER NOT NULL, `durationMinutes` INTEGER NOT NULL, `fareTotalPaise` INTEGER NOT NULL, `chargedPaise` INTEGER NOT NULL, `gstPaise` INTEGER NOT NULL, `pilotEarningPaise` INTEGER NOT NULL, `platformCommissionPaise` INTEGER NOT NULL, `pilotName` TEXT, `pilotRating` REAL, `vehicleModel` TEXT, `vehiclePlate` TEXT, `ratingStars` INTEGER, `ratingTags` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`rideId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_rides_createdAt` ON `cached_rides` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_rides_status` ON `cached_rides` (`status`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `active_ride_pointer` (`slot` INTEGER NOT NULL, `rideId` TEXT NOT NULL, `lastKnownStatus` TEXT NOT NULL, `lastKnownVersion` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`slot`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `pending_ride_requests` (`idempotencyKey` TEXT NOT NULL, `quoteId` TEXT NOT NULL, `expectedTotalPaise` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`idempotencyKey`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `pending_payments` (`paymentId` TEXT NOT NULL, `idempotencyKey` TEXT NOT NULL, `amountPaise` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`paymentId`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_payments_idempotencyKey` ON `pending_payments` (`idempotencyKey`)")
            }
        }

        @Volatile
        private var INSTANCE: PayLiftDatabase? = null

        fun getDatabase(context: Context): PayLiftDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, PayLiftDatabase::class.java, NAME)
                    .addMigrations(MIGRATION_1_2)
                    // No destructive fallback: a missing migration must fail loudly in testing.
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
