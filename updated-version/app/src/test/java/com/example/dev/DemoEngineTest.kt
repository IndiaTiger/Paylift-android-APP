package com.example.dev

import android.app.Application
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.auth.InMemoryTokenStore
import com.example.config.AppConfig
import com.example.core.AppError
import com.example.core.Outcome
import com.example.data.PayLiftDatabase
import com.example.di.AppContainer
import com.example.domain.RideStatus
import com.example.services.CheckoutResult
import com.example.services.LedgerType
import com.example.services.PaymentStatus
import com.example.services.SosResult
import com.example.services.remote.PollingNotificationService
import com.example.ui.AppNavTab
import com.example.ui.AuthUiState
import com.example.ui.PayLiftViewModel
import com.example.ui.QuoteUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Demo engine rules (Robolectric only for org.json). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DemoEngineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() = scope.cancel()

    // Simulator driven manually (huge step interval).
    private fun engine(store: DemoEngine.Store = DemoEngine.MemoryStore()) = DemoEngine(store, scope, stepMs = 3_600_000)

    private suspend fun DemoEngine.signIn() {
        val c = (auth.requestOtp(DemoEngine.DEMO_PHONE) as Outcome.Success).value
        assertTrue(auth.verifyOtp(c, DemoEngine.DEMO_OTP) is Outcome.Success)
    }

    @Test
    fun `demo credentials sign in and no code is revealed to the app`() = runBlocking {
        val e = engine()
        val c = (e.auth.requestOtp("+919999999999") as Outcome.Success).value
        assertNull(c.devOtp)
        assertTrue(e.auth.verifyOtp(c, "123456") is Outcome.Success)
        assertTrue(e.auth.isSignedIn)
    }

    @Test
    fun `wrong demo OTP fails and locks after 5 attempts`() = runBlocking {
        val e = engine()
        val c = (e.auth.requestOtp(DemoEngine.DEMO_PHONE) as Outcome.Success).value
        repeat(5) { assertEquals("OTP_INVALID", ((e.auth.verifyOtp(c, "000000") as Outcome.Failure).error as AppError.Api).code) }
        assertEquals("OTP_LOCKED", ((e.auth.verifyOtp(c, "123456") as Outcome.Failure).error as AppError.Api).code)
        assertFalse(e.auth.isSignedIn)
    }

    @Test
    fun `other phone numbers are refused - no SMS provider in demo`() = runBlocking {
        val r = engine().auth.requestOtp("+919812345678")
        assertTrue((r as Outcome.Failure).error is AppError.Api)
    }

    @Test
    fun `nothing works before sign-in`() = runBlocking {
        val e = engine()
        assertEquals(AppError.Unauthorized, (e.walletService.wallet() as Outcome.Failure).error)
        assertEquals(AppError.Unauthorized, (e.paymentService.createOrder(10_000, "k") as Outcome.Failure).error)
    }

    @Test
    fun `full flow - top-up, quote, hold, simulated pilot, capture, wallet reconciles`() = runBlocking {
        val e = engine()
        e.signIn()
        assertEquals(0L, e.wallet().balancePaise) // no free money
        val order = (e.paymentService.createOrder(50_000, "t1") as Outcome.Success).value
        val checkout = (e.checkout.launch(order) as Outcome.Success).value
        assertEquals(PaymentStatus.SUCCESS, (e.paymentService.verify(order, checkout) as Outcome.Success).value.first)
        e.paymentService.verify(order, checkout) // replay: no double credit
        assertEquals(50_000L, e.wallet().balancePaise)

        val places = (e.maps.search("") as Outcome.Success).value
        val q = (e.rideService.quote("cab_mini", places[0], places[1]) as Outcome.Success).value
        assertTrue(q.fare.isConsistent())
        val ride = (e.rideService.request(q.quoteId, q.fare.totalPaise, "r1") as Outcome.Success).value
        assertEquals(ride.rideId, (e.rideService.request(q.quoteId, q.fare.totalPaise, "r1") as Outcome.Success).value.rideId)
        assertEquals(q.fare.totalPaise, e.wallet().heldPaise)

        val seen = mutableListOf<RideStatus>()
        repeat(20) { e.simulatorStep(); seen += (e.rideService.get(ride.rideId) as Outcome.Success).value.status }
        val done = (e.rideService.get(ride.rideId) as Outcome.Success).value
        assertEquals(RideStatus.COMPLETED, done.status)
        assertEquals(q.fare.totalPaise, done.chargedPaise) // displayed == charged
        seen.distinct().zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", RideStatus.canTransition(a, b)) }
        assertEquals(50_000L - q.fare.totalPaise, e.wallet().balancePaise)
        assertEquals(0L, e.wallet().heldPaise)
        assertTrue(e.reconciles())
        assertEquals(5, (e.rideService.rate(ride.rideId, 5, listOf("Polite")) as Outcome.Success).value.ratingStars)
        assertTrue(e.rideService.rate(ride.rideId, 1, emptyList()) is Outcome.Failure)
    }

    @Test
    fun `forged signature, insufficient funds, cancel releases hold, SOS truthful`() = runBlocking {
        val e = engine()
        e.signIn()
        val order = (e.paymentService.createOrder(30_000, "t") as Outcome.Success).value
        val forged = e.paymentService.verify(order, CheckoutResult.Completed("pay_x", "00"))
        assertEquals("INVALID_SIGNATURE", ((forged as Outcome.Failure).error as AppError.Api).code)
        assertEquals(0L, e.wallet().balancePaise)
        val p = DemoEngine.PLACES
        val q = (e.rideService.quote("cab_mini", p[0], p[1]) as Outcome.Success).value
        assertEquals("INSUFFICIENT_FUNDS", ((e.rideService.request(q.quoteId, q.fare.totalPaise, "x") as Outcome.Failure).error as AppError.Api).code)
        e.paymentService.verify(order, (e.checkout.launch(order) as Outcome.Success).value)
        val ride = (e.rideService.request(q.quoteId, q.fare.totalPaise, "y") as Outcome.Success).value
        assertEquals(RideStatus.CANCELLED, (e.rideService.cancel(ride.rideId, null) as Outcome.Success).value.status)
        assertTrue(e.rideService.cancel(ride.rideId, null) is Outcome.Failure)
        assertEquals(30_000L, e.wallet().availablePaise)
        assertTrue(e.reconciles())
        assertTrue((e.safety.triggerSos(null, null, null) as Outcome.Success).value is SosResult.Unavailable)
        assertTrue((e.rideService.requestMaskedCall(ride.rideId) as Outcome.Failure).error is AppError.NotConfigured)
    }

    @Test
    fun `state survives process death`() = runBlocking {
        val store = DemoEngine.MemoryStore()
        val e1 = engine(store)
        e1.signIn()
        val order = (e1.paymentService.createOrder(50_000, "t") as Outcome.Success).value
        e1.paymentService.verify(order, (e1.checkout.launch(order) as Outcome.Success).value)
        val p = DemoEngine.PLACES
        val q = (e1.rideService.quote("cab_sedan", p[2], p[9]) as Outcome.Success).value
        val ride = (e1.rideService.request(q.quoteId, q.fare.totalPaise, "r") as Outcome.Success).value
        e1.simulatorStep(); e1.simulatorStep()
        // "Process death": a new engine over the same persisted store.
        val e2 = engine(store)
        assertTrue(e2.auth.isSignedIn)
        val active = (e2.rideService.active() as Outcome.Success).value
        assertEquals(ride.rideId, active!!.rideId)
        assertEquals(RideStatus.PILOT_ACCEPTED, active.status)
        assertEquals(q.fare.totalPaise, e2.wallet().heldPaise)
        repeat(20) { e2.simulatorStep() }
        assertEquals(RideStatus.COMPLETED, (e2.rideService.get(ride.rideId) as Outcome.Success).value.status)
        assertTrue(e2.reconciles())
    }
}

/** The whole demo journey through the real ViewModel + repositories + Room, backed by the demo engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DemoFlowViewModelTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!cond()) {
            // Advance the paused main looper clock so delay()-based polling on Main runs.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(20)
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun `login, book, ride completes, wallet, history and profile`() {
        val engine = DemoEngine(DemoEngine.MemoryStore(), scope, stepMs = 100)
        val db = Room.inMemoryDatabaseBuilder(app, PayLiftDatabase::class.java).allowMainThreadQueries().build()
        val container = AppContainer(
            AppConfig("", "demo", "", "", "", "", "demo", devToolsEnabled = true, demoMode = true), db.cacheDao(), InMemoryTokenStore(),
            engine.auth, engine.users, engine.walletService, engine.paymentService, engine.checkout, engine.rideService,
            engine.maps, engine.location, engine.pilots, PollingNotificationService(engine.rideService, 100), engine.safety,
        )
        val vm = PayLiftViewModel(app, container)
        listOf(vm.userProfile, vm.transactions, vm.pastRides).forEach { f -> kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main.immediate) { f.collect { } } }
        assertTrue(vm.isDemoMode)

        // Login
        assertEquals(AuthUiState.SignedOut, vm.auth.value)
        vm.requestOtp("9999999999")
        waitFor("otp") { vm.auth.value is AuthUiState.OtpSent }
        vm.verifyOtp("123456")
        waitFor("signed in") { vm.auth.value == AuthUiState.SignedIn }

        // Wallet top-up via the demo gateway
        vm.topUpWallet(50_000)
        waitFor("credited") { vm.userProfile.value.walletAvailablePaise == 50_000L }

        // Locations + fare
        waitFor("places") { vm.placeSuggestions.value.size >= 2 }
        vm.setPickup(vm.placeSuggestions.value[0])
        vm.setDropoff(vm.placeSuggestions.value[1])
        waitFor("quote") { vm.quote.value is QuoteUiState.Ready }
        val total = vm.currentFareBreakdown.value!!.totalPaise

        // Booking -> simulated pilot -> completion
        vm.requestRide()
        waitFor("active ride") { vm.activeRide.value != null }
        waitFor("pilot assigned") { vm.activeRide.value?.pilot?.name?.startsWith("Vikram") == true || vm.showCompletedReceiptModal.value != null }
        waitFor("completed receipt") { vm.showCompletedReceiptModal.value != null }
        assertNull(vm.activeRide.value)
        assertEquals(total / 100.0, vm.showCompletedReceiptModal.value!!.grossFare, 0.0)

        // Wallet + history + profile
        waitFor("wallet charged") { vm.userProfile.value.walletAvailablePaise == 50_000L - total }
        vm.selectTab(AppNavTab.ACTIVITY)
        waitFor("history") { vm.pastRides.value.any { it.status == "COMPLETED" } }
        waitFor("ledger") { vm.transactions.value.size >= 3 }
        vm.updateEmergencyContact("Asha", "+91 98000 00000", "Friend", true)
        waitFor("profile") { vm.userProfile.value.emergencyContactName == "Asha" }
        assertEquals("+919999999999", vm.userProfile.value.phone)
        assertNotNull(engine.reconciles().takeIf { it })
    }
}
