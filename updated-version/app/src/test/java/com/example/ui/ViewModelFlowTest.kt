package com.example.ui

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.auth.InMemoryTokenStore
import com.example.auth.Tokens
import com.example.config.AppConfig
import com.example.core.AppError
import com.example.core.Outcome
import com.example.data.PayLiftDatabase
import com.example.di.AppContainer
import com.example.domain.LocationPoint
import com.example.domain.RideStatus
import com.example.services.AuthService
import com.example.services.LocationService
import com.example.services.MapsService
import com.example.services.NearbyPilot
import com.example.services.NotificationService
import com.example.services.OtpChallenge
import com.example.services.PilotService
import com.example.services.ProfilePatch
import com.example.services.Ride
import com.example.services.Route
import com.example.services.SafetyService
import com.example.services.SavedAddress
import com.example.services.SosResult
import com.example.services.UserAccount
import com.example.services.UserService
import com.example.services.WalletBalance
import com.example.services.WalletService
import com.example.testing.FakeCheckout
import com.example.testing.FakePaymentService
import com.example.testing.FakeRideService
import com.example.usecase.RequestOtpUseCase
import com.example.usecase.RequestRideUseCase
import com.example.usecase.TopUpWalletUseCase
import com.example.testing.sampleQuote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

class UseCaseTest {
    @Test
    fun `phone normalisation`() {
        assertEquals("+919897012345", RequestOtpUseCase.normalizeIndianPhone("98970 12345"))
        assertEquals("+919897012345", RequestOtpUseCase.normalizeIndianPhone("+91 98970-12345"))
        assertEquals("+919897012345", RequestOtpUseCase.normalizeIndianPhone("919897012345"))
        assertNull(RequestOtpUseCase.normalizeIndianPhone("12345"))
    }

    @Test
    fun `top-up amount parsing and bounds`() {
        assertEquals(25_000L, TopUpWalletUseCase.parseAmount("250"))
        assertNull(TopUpWalletUseCase.parseAmount("2.505"))
    }

    @Test
    fun `ride request guards`() = runBlocking {
        val rides = com.example.repository.RideRepository(FakeRideService(), object : NotificationService {
            override fun rideUpdates(rideId: String): Flow<Outcome<Ride>> = emptyFlow()
        }, NoopDao)
        val uc = RequestRideUseCase(rides, now = { 1_000 })
        assertEquals("NO_QUOTE", ((uc(null, 10_000_000) as Outcome.Failure).error as AppError.Api).code)
        assertEquals("QUOTE_EXPIRED", ((uc(sampleQuote(expiresAt = 999), 10_000_000) as Outcome.Failure).error as AppError.Api).code)
        assertEquals("INSUFFICIENT_FUNDS", ((uc(sampleQuote(), 100) as Outcome.Failure).error as AppError.Api).code)
        val bad = sampleQuote().let { it.copy(fare = it.fare.copy(totalPaise = 1)) }
        assertEquals("BAD_QUOTE", ((uc(bad, 10_000_000) as Outcome.Failure).error as AppError.Api).code)
    }
}

/** Minimal DAO stub for pure use-case tests that never reach the cache. */
private val NoopDao: com.example.data.CacheDao = java.lang.reflect.Proxy.newProxyInstance(
    com.example.data.CacheDao::class.java.classLoader, arrayOf(com.example.data.CacheDao::class.java)
) { _, m, _ -> throw UnsupportedOperationException("cache not expected: ${m.name}") } as com.example.data.CacheDao

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewModelFlowTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var payments: FakePaymentService
    private lateinit var rides: FakeRideService
    private lateinit var rideUpdates: MutableSharedFlow<Outcome<Ride>>
    private var sosResult: Outcome<SosResult> = Outcome.Success(SosResult.Unavailable("Emergency dispatch integration is not configured."))

    private val place1 = LocationPoint("Clock Tower", "", 30.3255, 78.0436)
    private val place2 = LocationPoint("Rajpur Road", "", 30.3580, 78.0720)

    private fun container(signedIn: Boolean = true): AppContainer {
        val db = Room.inMemoryDatabaseBuilder(app, PayLiftDatabase::class.java).allowMainThreadQueries().build()
        payments = FakePaymentService()
        rides = FakeRideService()
        rideUpdates = MutableSharedFlow(replay = 1)
        val tokens = InMemoryTokenStore(if (signedIn) Tokens("a", "r") else null)
        return AppContainer(
            config = AppConfig("http://test/", "test", "", "", "", "", "dev", true),
            cache = db.cacheDao(),
            tokenStore = tokens,
            authService = object : AuthService {
                override val isSignedIn get() = tokens.read() != null
                override val sessionExpired: Flow<Unit> = emptyFlow()
                override suspend fun requestOtp(phone: String) = Outcome.Success(OtpChallenge(phone, "req", 300, "123456"))
                override suspend fun verifyOtp(challenge: OtpChallenge, otp: String): Outcome<UserAccount> =
                    if (otp == "123456") { tokens.write(Tokens("a", "r")); Outcome.Success(user) } else Outcome.Failure(AppError.Api(401, "OTP_INVALID", "Incorrect OTP"))
                override suspend fun logout(): Outcome<Unit> { tokens.clear(); return Outcome.Success(Unit) }
                override suspend fun deleteAccount(): Outcome<Unit> = Outcome.Failure(AppError.Api(409, "WALLET_NOT_EMPTY", "Wallet balance must be withdrawn"))
            },
            userService = object : UserService {
                override suspend fun me() = Outcome.Success(user)
                override suspend fun update(patch: ProfilePatch) = Outcome.Success(user.copy(emergencyContactName = patch.emergencyContactName ?: ""))
                override suspend fun addresses() = Outcome.Success(emptyList<SavedAddress>())
                override suspend fun addAddress(label: String, place: LocationPoint) = Outcome.Success(SavedAddress("a", label, place))
                override suspend fun deleteAddress(addressId: String) = Outcome.Success(Unit)
            },
            walletService = object : WalletService {
                override suspend fun wallet() = Outcome.Success(payments.wallet())
                override suspend fun transactions(limit: Int) = Outcome.Success(emptyList<com.example.services.LedgerEntry>())
            },
            paymentService = payments,
            paymentCheckout = FakeCheckout(),
            rideService = rides,
            mapsService = object : MapsService {
                override suspend fun search(query: String) = Outcome.Success(listOf(place1, place2))
                override suspend fun reverseGeocode(lat: Double, lng: Double) = Outcome.Success(place1.copy(latitude = lat, longitude = lng))
                override suspend fun route(origin: LocationPoint, destination: LocationPoint) = Outcome.Success(Route(5200, 16, emptyList(), "test"))
            },
            locationService = object : LocationService {
                override suspend fun currentLocation() = Outcome.Success(30.3255 to 78.0436)
                override fun hasPermission() = true
            },
            pilotService = object : PilotService {
                override suspend fun nearby(lat: Double, lng: Double, vehicleId: String?) = Outcome.Success(listOf(NearbyPilot("p", listOf("cab_mini"), lat to lng, 500, 2)))
                override suspend fun setAvailability(available: Boolean) = Outcome.Success(Unit)
                override suspend fun updateLocation(lat: Double, lng: Double) = Outcome.Success(Unit)
                override suspend fun accept(rideId: String) = rides.get(rideId)
                override suspend fun markArriving(rideId: String) = rides.get(rideId)
                override suspend fun markArrived(rideId: String) = rides.get(rideId)
                override suspend fun verifyStartOtp(rideId: String, otp: String) = rides.get(rideId)
                override suspend fun start(rideId: String) = rides.get(rideId)
                override suspend fun complete(rideId: String) = rides.get(rideId)
            },
            notificationService = object : NotificationService {
                override fun rideUpdates(rideId: String): Flow<Outcome<Ride>> = rideUpdates
            },
            safetyService = object : SafetyService {
                override suspend fun triggerSos(lat: Double?, lng: Double?, rideId: String?) = sosResult
            },
        )
    }

    private val user = UserAccount("usr_1", "+919800000000", "Asha", "", "", "", "", false)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun vm(signedIn: Boolean = true): PayLiftViewModel {
        val vm = PayLiftViewModel(app, container(signedIn))
        // Keep WhileSubscribed flows alive like the UI would.
        listOf(vm.userProfile, vm.transactions, vm.pastRides).forEach { flow ->
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { flow.collect { } }
        }
        idle()
        return vm
    }

    @Before fun setUpMain() { idle() }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!cond()) {
            idle()
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun `sign-in flow with OTP`() {
        val vm = vm(signedIn = false)
        assertEquals(AuthUiState.SignedOut, vm.auth.value)
        vm.requestOtp("98000 00000")
        waitFor("otp sent") { vm.auth.value is AuthUiState.OtpSent }
        vm.verifyOtp("000000")
        waitFor("error") { vm.authOp.value is OpState.Failed }
        vm.verifyOtp("123456")
        waitFor("signed in") { vm.auth.value == AuthUiState.SignedIn }
    }

    @Test
    fun `no fare is shown until the server quote arrives, then booking holds and cancel releases`() {
        val vm = vm()
        assertNull(vm.currentFareBreakdown.value)
        assertTrue(vm.quote.value is QuoteUiState.NeedsInput)
        // Wallet funded through the payment flow only.
        vm.topUpWallet(50_000)
        waitFor("top-up") { vm.userProfile.value.walletAvailablePaise == 50_000L }

        vm.setPickup(place1)
        vm.setDropoff(place2)
        waitFor("quote") { vm.quote.value is QuoteUiState.Ready }
        assertEquals(19425L, vm.currentFareBreakdown.value!!.totalPaise)
        assertEquals(2, vm.vehicles.value.first { it.id == "cab_mini" }.etaMins)

        vm.requestRide()
        vm.requestRide() // double tap
        waitFor("active ride") { vm.activeRide.value != null }
        assertEquals(1, rides.requestsByKey.size)
        val active = vm.activeRide.value!!
        assertEquals(RideStatus.SEARCHING, active.status)
        assertEquals(19425L, active.fareBreakdown.totalPaise)

        vm.cancelActiveRide()
        waitFor("cancelled") { vm.activeRide.value == null }
        assertEquals(RideStatus.CANCELLED, rides.requestsByKey.values.single().status)
    }

    @Test
    fun `completion comes only from the server and shows the charged receipt`() {
        val vm = vm()
        vm.topUpWallet(50_000)
        waitFor("top-up") { vm.userProfile.value.walletAvailablePaise == 50_000L }
        vm.setPickup(place1); vm.setDropoff(place2)
        waitFor("quote") { vm.quote.value is QuoteUiState.Ready }
        vm.requestRide()
        waitFor("active") { vm.activeRide.value != null }
        val ride = rides.requestsByKey.values.single()
        // Out-of-order snapshot (older version) is ignored.
        rideUpdates.tryEmit(Outcome.Success(ride.copy(status = RideStatus.IN_PROGRESS, version = 8)))
        waitFor("in progress") { vm.activeRide.value?.status == RideStatus.IN_PROGRESS }
        rideUpdates.tryEmit(Outcome.Success(ride.copy(status = RideStatus.ASSIGNED, version = 3)))
        idle()
        assertEquals(RideStatus.IN_PROGRESS, vm.activeRide.value?.status)
        rideUpdates.tryEmit(Outcome.Success(ride.copy(status = RideStatus.COMPLETED, version = 9, chargedPaise = ride.fare.totalPaise)))
        waitFor("receipt") { vm.showCompletedReceiptModal.value != null }
        assertNull(vm.activeRide.value)
        assertEquals(194.25, vm.showCompletedReceiptModal.value!!.grossFare, 0.0)
    }

    @Test
    fun `insufficient balance routes to wallet without creating a ride`() {
        val vm = vm()
        vm.setPickup(place1); vm.setDropoff(place2)
        waitFor("quote") { vm.quote.value is QuoteUiState.Ready }
        vm.requestRide()
        waitFor("failed") { vm.requestOp.value is OpState.Failed }
        assertEquals(AppNavTab.WALLET, vm.currentTab.value)
        assertTrue(rides.requestsByKey.isEmpty())
    }

    @Test
    fun `SOS never reports success when the integration is unavailable`() {
        val vm = vm()
        vm.openSosModal(true)
        vm.triggerSos()
        waitFor("sos result") { vm.sosState.value !is SosUiState.Sending && vm.sosState.value != SosUiState.Idle }
        assertTrue(vm.sosState.value is SosUiState.Unavailable)
        sosResult = Outcome.Failure(AppError.Offline)
        vm.openSosModal(false); vm.openSosModal(true); vm.triggerSos()
        waitFor("sos offline") { vm.sosState.value is SosUiState.Unavailable }
    }

    @Test
    fun `failed top-up shows failure and does not change the balance`() {
        val vm = vm()
        payments.failVerifyWith = AppError.Api(400, "INVALID_SIGNATURE", "Payment signature verification failed")
        vm.topUpWallet(10_000)
        waitFor("failed") { vm.topUpOp.value is OpState.Failed }
        assertEquals(0L, vm.userProfile.value.walletAvailablePaise)
    }

    @Test
    fun `masked call reports unavailable instead of faking a call`() {
        val vm = vm()
        vm.topUpWallet(50_000)
        waitFor("top-up") { vm.userProfile.value.walletAvailablePaise == 50_000L }
        vm.setPickup(place1); vm.setDropoff(place2)
        waitFor("quote") { vm.quote.value is QuoteUiState.Ready }
        vm.requestRide()
        waitFor("active") { vm.activeRide.value != null }
        vm.openCallModal(true)
        waitFor("call state") { vm.callState.value is CallUiState.Unavailable }
        assertNotNull(vm.activeRide.value)
    }
}
