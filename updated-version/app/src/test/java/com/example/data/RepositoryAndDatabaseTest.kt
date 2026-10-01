package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.example.core.AppError
import com.example.core.Outcome
import com.example.domain.RideStatus
import com.example.repository.PaymentRepository
import com.example.repository.ProfileRepository
import com.example.repository.RideRepository
import com.example.repository.TopUpResult
import com.example.repository.toCache
import com.example.services.CheckoutResult
import com.example.services.LedgerType
import com.example.services.PaymentStatus
import com.example.services.UserAccount
import com.example.services.UserService
import com.example.services.WalletService
import com.example.testing.FakeCheckout
import com.example.testing.FakePaymentService
import com.example.testing.FakeRideService
import com.example.testing.StaticNotifications
import com.example.testing.ledgerEntry
import com.example.testing.sampleQuote
import com.example.testing.sampleRide
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryAndDatabaseTest {
    private lateinit var db: PayLiftDatabase
    private lateinit var dao: CacheDao
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val walletService = object : WalletService {
        var balance = 0L
        override suspend fun wallet() = Outcome.Success(com.example.services.WalletBalance(balance, 0, balance, "INR"))
        override suspend fun transactions(limit: Int) = Outcome.Success(listOf(ledgerEntry("le1", LedgerType.TOPUP_CREDIT, balance)).filter { balance > 0 })
    }
    private val userService = object : UserService {
        override suspend fun me() = Outcome.Success(UserAccount("usr_1", "+919800000000", "", "", "", "", "", false))
        override suspend fun update(patch: com.example.services.ProfilePatch) = me()
        override suspend fun addresses() = Outcome.Success(emptyList<com.example.services.SavedAddress>())
        override suspend fun addAddress(label: String, place: com.example.domain.LocationPoint) = Outcome.Failure(AppError.Unexpected("n/a"))
        override suspend fun deleteAddress(addressId: String) = Outcome.Success(Unit)
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, PayLiftDatabase::class.java).allowMainThreadQueries().build()
        dao = db.cacheDao()
    }

    @After fun tearDown() = db.close()

    private fun payments(fake: FakePaymentService, checkout: FakeCheckout = FakeCheckout()): PaymentRepository {
        val profile = ProfileRepository(userService, object : WalletService by walletService {
            override suspend fun wallet() = Outcome.Success(fake.wallet())
        }, dao)
        return PaymentRepository(fake, checkout, dao, profile)
    }

    // ------------------------------------------------------------------ Payments
    @Test
    fun `successful top-up is credited by the server and cached from the server snapshot`() = runBlocking {
        val fake = FakePaymentService()
        val r = payments(fake).topUp(50_000, "k1")
        assertTrue((r as Outcome.Success).value is TopUpResult.Credited)
        assertEquals(50_000L, dao.wallet().first()!!.balancePaise)
        assertTrue(dao.pendingPayments().isEmpty())
    }

    @Test
    fun `failed and cancelled checkouts never credit`() = runBlocking {
        val fake = FakePaymentService()
        val failed = payments(fake, FakeCheckout { Outcome.Success(CheckoutResult.Failed("declined")) }).topUp(10_000, "f")
        assertTrue((failed as Outcome.Success).value is TopUpResult.Failed)
        val cancelled = payments(fake, FakeCheckout { Outcome.Success(CheckoutResult.Cancelled) }).topUp(10_000, "c")
        assertEquals(TopUpResult.Cancelled, (cancelled as Outcome.Success).value)
        assertEquals(0L, fake.balance)
    }

    @Test
    fun `forged checkout signature cannot credit`() = runBlocking {
        val fake = FakePaymentService()
        val r = payments(fake, FakeCheckout { Outcome.Success(CheckoutResult.Completed("pp", "forged")) }).topUp(10_000, "x")
        assertTrue(r is Outcome.Failure)
        assertEquals(0L, fake.balance)
    }

    @Test
    fun `double-tap top-up with the same key credits once`() = runBlocking {
        val fake = FakePaymentService()
        val repo = payments(fake)
        val results = (1..5).map { async { repo.topUp(20_000, "same-key") } }.awaitAll()
        assertEquals(20_000L, fake.balance)
        assertEquals(1, fake.orders.size)
        assertEquals(1, results.count { (it as? Outcome.Success)?.value is TopUpResult.Credited })
    }

    @Test
    fun `network loss after paying keeps the payment pending, then reconciliation settles it`() = runBlocking {
        val fake = FakePaymentService().apply { failVerifyWith = AppError.Timeout }
        val repo = payments(fake)
        val r = repo.topUp(30_000, "crash")
        assertEquals(TopUpResult.PendingConfirmation, (r as Outcome.Success).value)
        assertEquals(1, dao.pendingPayments().size)
        assertEquals(0L, fake.balance) // nothing credited locally
        // Webhook settled it server side while the app was dead.
        fake.statusOverride = PaymentStatus.SUCCESS
        fake.balance = 30_000
        assertEquals(1, repo.reconcilePending())
        assertTrue(dao.pendingPayments().isEmpty())
        assertEquals(30_000L, dao.wallet().first()!!.balancePaise)
    }

    // ------------------------------------------------------------------ Rides
    @Test
    fun `double-tap ride request creates one ride`() = runBlocking {
        val fake = FakeRideService()
        val repo = RideRepository(fake, StaticNotifications(fake), dao)
        val results = (1..3).map { async { repo.request(sampleQuote()) } }.awaitAll()
        assertEquals(1, results.count { it is Outcome.Success })
        assertEquals(1, fake.requestsByKey.size)
    }

    @Test
    fun `app killed during ride request - recovery resends with the same idempotency key`() = runBlocking {
        val fake = FakeRideService().apply { failNextRequestWith = AppError.Timeout }
        val repo = RideRepository(fake, StaticNotifications(fake), dao)
        assertTrue(repo.request(sampleQuote()) is Outcome.Failure)
        val pending = dao.pendingRideRequest()!!
        // "Process death": a brand-new repository instance over the same database.
        val restarted = RideRepository(fake, StaticNotifications(fake), dao)
        val recovered = restarted.recover()
        assertEquals("ride_1", (recovered as Outcome.Success).value!!.rideId)
        assertEquals(1, fake.requestsByKey.size) // not a second ride / second hold
        assertTrue(fake.requestsByKey.containsKey(pending.idempotencyKey))
        assertNull(dao.pendingRideRequest())
        assertEquals("ride_1", dao.activeRide()!!.rideId)
    }

    @Test
    fun `stale ride snapshots never overwrite newer ones`() = runBlocking {
        dao.upsertRideIfNewer(sampleRide(status = RideStatus.IN_PROGRESS, version = 7).toCache())
        dao.upsertRideIfNewer(sampleRide(status = RideStatus.ASSIGNED, version = 3).toCache())
        assertEquals("IN_PROGRESS", dao.ride("ride_1")!!.status)
    }

    @Test
    fun `terminal ride clears the active pointer`() = runBlocking {
        val fake = FakeRideService()
        val repo = RideRepository(fake, StaticNotifications(fake), dao)
        repo.track(sampleRide(status = RideStatus.ARRIVED, version = 5))
        assertEquals("ride_1", dao.activeRide()!!.rideId)
        repo.track(sampleRide(status = RideStatus.COMPLETED, version = 9))
        assertNull(dao.activeRide())
        assertEquals(1, dao.rideHistory().first().size)
    }

    // ------------------------------------------------------------------ Schema
    @Test
    fun `ledger cache enforces unique references`() = runBlocking {
        dao.insertLedger(listOf(ledgerEntry("a", LedgerType.TOPUP_CREDIT, 100).toCache()))
        val dup = ledgerEntry("b", LedgerType.TOPUP_CREDIT, 100).toCache().copy(reference = "ref:a")
        try {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO cached_ledger_entries (id,type,amountPaise,balanceAfterPaise,heldAfterPaise,reference,description,createdAt) VALUES ('${dup.id}','x',1,1,0,'${dup.reference}','d',1)"
            )
            throw AssertionError("duplicate reference accepted")
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // expected
        }
    }

    @Test
    fun `sign-out clears every cached table`() = runBlocking {
        dao.upsertWallet(CachedWallet("me", 1, 0, 1, 0))
        dao.setActiveRide(ActiveRidePointer(0, "r", "SEARCHING", 1, 0))
        dao.insertPendingPayment(PendingPayment("p", "k", 1, 0))
        dao.clearAll()
        assertNull(dao.wallet().first())
        assertNull(dao.activeRide())
        assertTrue(dao.pendingPayments().isEmpty())
    }
}

/**
 * Migration test from the schema the prototype actually shipped (v1, identity hash 08983147...,
 * captured from its generated Room code into test resources) to v2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @Before fun clean() { context.deleteDatabase(name) }
    @After fun cleanup() { context.deleteDatabase(name) }

    private fun createV1WithData() {
        val v1Sql = javaClass.classLoader!!.getResource("room_v1_schema.sql")!!.readText()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    v1Sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
                    db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                    db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '08983147f7db20f8a2537d14d42671f0')")
                    // The prototype's seeded, locally-invented money.
                    db.execSQL("INSERT INTO user_profile VALUES (1,'Mohit Tyagi','m@x','+91','P','+91','S',650.0,340.0,1,150.0,500.0,1,'Wallet','Classic Blue')")
                    db.execSQL("INSERT INTO wallet_transactions (transactionId,timestamp,type,category,amount,balanceAfter,gateway,status,hmacSignature,referenceId,description) VALUES ('T1',1,'credit','TOPUP',500.0,500.0,'Razorpay','SUCCESS','sig','ref','seed')")
                    db.execSQL("INSERT INTO ride_records (rideId,timestamp,pickupName,dropName,distanceKm,durationMin,vehicleCategory,vehicleModel,vehiclePlate,pilotName,pilotRating,grossFare,pilotEarnings,platformCut,gstTax,status,userRating,feedbackTags) VALUES ('PL-1',1,'a','b',1.0,1,'c','m','p','n',4.9,125.0,106.0,18.0,5.9,'COMPLETED',5,'')")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        )
        helper.writableDatabase.close()
        helper.close()
    }

    @Test
    fun `v1 to v2 migrates without data loss and without trusting local money`() = runBlocking {
        createV1WithData()
        // No destructive fallback configured: Room would throw if the migration were missing or wrong.
        val db = Room.databaseBuilder(context, PayLiftDatabase::class.java, name)
            .addMigrations(PayLiftDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.cacheDao()
            // Room validated the v2 schema on open; the new cache starts empty (balances come from the server).
            assertNull(dao.wallet().first())
            assertTrue(dao.ledger().first().isEmpty())
            // Legacy rows are preserved, not destroyed.
            val c = db.openHelper.readableDatabase
            c.query("SELECT COUNT(*) FROM legacy_v1_wallet_transactions").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            c.query("SELECT COUNT(*) FROM legacy_v1_ride_records").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            c.query("SELECT walletBalance FROM legacy_v1_user_profile").use { it.moveToFirst(); assertEquals(650.0, it.getDouble(0), 0.0) }
            c.query("PRAGMA user_version").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
            assertFalse(c.query("SELECT name FROM sqlite_master WHERE name='wallet_transactions'").use { it.moveToFirst() })
        } finally {
            db.close()
        }
    }
}
