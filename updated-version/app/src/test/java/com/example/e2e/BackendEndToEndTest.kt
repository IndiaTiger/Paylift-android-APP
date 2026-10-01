package com.example.e2e

import com.example.api.ApiClient
import com.example.auth.InMemoryTokenStore
import com.example.core.AppError
import com.example.core.Outcome
import com.example.dev.TestGatewayCheckout
import com.example.domain.LocationPoint
import com.example.domain.RideStatus
import com.example.services.CheckoutResult
import com.example.services.LedgerType
import com.example.services.PaymentStatus
import com.example.services.SosResult
import com.example.services.remote.RemoteAuthService
import com.example.services.remote.RemoteMapsService
import com.example.services.remote.RemotePaymentService
import com.example.services.remote.RemotePilotService
import com.example.services.remote.RemoteRideService
import com.example.services.remote.RemoteSafetyService
import com.example.services.remote.RemoteUserService
import com.example.services.remote.RemoteWalletService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files

/**
 * End-to-end: the real Android client stack (ApiClient + Remote* services + debug test-gateway
 * checkout) against the real test backend (backend/, Node + SQLite) started as a subprocess.
 *
 * AUTH -> USER -> LOCATION -> PAYMENT (test gateway) -> WALLET -> RIDE -> SAFETY.
 * Skipped (not failed) only when `node` is not installed.
 */
class BackendEndToEndTest {

    companion object {
        private var process: Process? = null
        private lateinit var baseUrl: String
        private lateinit var dataDir: File

        private fun backendDir(): File = listOf(File("../backend"), File("backend")).first { File(it, "src/server.js").exists() }

        @BeforeClass @JvmStatic
        fun startBackend() {
            val nodeAvailable = runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
            assumeTrue("node is required for the end-to-end test", nodeAvailable)
            val port = ServerSocket(0).use { it.localPort }
            dataDir = Files.createTempDirectory("paylift-e2e").toFile()
            val pb = ProcessBuilder("node", "--disable-warning=ExperimentalWarning", "src/server.js")
                .directory(backendDir())
                .redirectErrorStream(true)
                .redirectOutput(File(dataDir, "server.log"))
            pb.environment().putAll(
                mapOf(
                    "NODE_ENV" to "test", "PORT" to "$port", "HOST" to "127.0.0.1",
                    "DATABASE_FILE" to File(dataDir, "e2e.db").absolutePath,
                    "AUTH_PROVIDER" to "dev", "PAYMENT_PROVIDER" to "test", "MAPS_PROVIDER" to "dev",
                    "DEV_PILOT_SIMULATOR" to "true", "DEV_SIM_STEP_MS" to "250",
                )
            )
            process = pb.start()
            baseUrl = "http://127.0.0.1:$port/"
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val up = runCatching { (URL(baseUrl + "health").openConnection() as HttpURLConnection).responseCode == 200 }.getOrDefault(false)
                if (up) return
                Thread.sleep(200)
            }
            error("backend did not start: " + File(dataDir, "server.log").readText())
        }

        @AfterClass @JvmStatic
        fun stopBackend() {
            process?.destroy()
            process?.waitFor()
        }
    }

    private class Client(baseUrl: String) {
        val tokens = InMemoryTokenStore()
        val api = ApiClient(baseUrl, tokens)
        val auth = RemoteAuthService(api, tokens)
        val users = RemoteUserService(api)
        val wallet = RemoteWalletService(api)
        val payments = RemotePaymentService(api)
        val checkout = TestGatewayCheckout(api)
        val rides = RemoteRideService(api)
        val maps = RemoteMapsService(api)
        val pilots = RemotePilotService(api)
        val safety = RemoteSafetyService(api)

        suspend fun signIn(phone: String) {
            val challenge = (auth.requestOtp(phone) as Outcome.Success).value
            assertNotNull("dev backend returns the OTP", challenge.devOtp)
            assertTrue(auth.verifyOtp(challenge, challenge.devOtp!!) is Outcome.Success)
        }
    }

    private fun <T> Outcome<T>.ok(): T = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> throw AssertionError("expected success, got $error")
    }

    @Test
    fun `full rider journey against the test backend`() = runBlocking {
        val c = Client(baseUrl)
        c.signIn("+919811100001")

        // USER
        assertEquals("+919811100001", c.users.me().ok().phone)
        assertEquals("Asha", c.users.update(com.example.services.ProfilePatch(name = "Asha")).ok().name)

        // WALLET starts at zero: no free money.
        assertEquals(0L, c.wallet.wallet().ok().balancePaise)

        // PAYMENT via the test gateway: order -> checkout -> server verification -> ledger credit.
        val order = c.payments.createOrder(50_000, "e2e-topup-1").ok()
        val checkout = c.checkout.launch(order).ok()
        assertTrue(checkout is CheckoutResult.Completed)
        val (status, balance) = c.payments.verify(order, checkout).ok()
        assertEquals(PaymentStatus.SUCCESS, status)
        assertEquals(50_000L, balance.balancePaise)
        // Replaying verification (e.g. a retry after a crash) does not credit twice.
        c.payments.verify(order, checkout).ok()
        assertEquals(50_000L, c.wallet.wallet().ok().balancePaise)
        // A forged signature is rejected.
        val order2 = c.payments.createOrder(10_000, "e2e-topup-2").ok()
        val forged = c.payments.verify(order2, CheckoutResult.Completed("pay_forged", "0".repeat(64)))
        assertEquals("INVALID_SIGNATURE", ((forged as Outcome.Failure).error as AppError.Api).code)
        assertEquals(50_000L, c.wallet.wallet().ok().balancePaise)

        // LOCATION (dev adapter, labelled as such)
        val places = c.maps.search("clock").ok()
        val pickup = places.first()
        val dropoff = c.maps.search("pacific").ok().first()
        assertTrue(c.maps.route(pickup, dropoff).ok().distanceMeters > 1000)
        val pinned = c.maps.reverseGeocode(30.3256, 78.0437).ok()
        assertEquals("Clock Tower (Ghanta Ghar)", pinned.name)

        // PILOTS nearby
        assertTrue(c.pilots.nearby(pickup.latitude, pickup.longitude, "cab_mini").ok().isNotEmpty())

        // RIDE: quote -> request (hold) -> simulated pilot lifecycle -> completion (capture)
        val quote = c.rides.quote("cab_mini", pickup, dropoff).ok()
        assertTrue(quote.fare.isConsistent())
        val ride = c.rides.request(quote.quoteId, quote.fare.totalPaise, "e2e-ride-1").ok()
        assertEquals(quote.fare.totalPaise, ride.fare.totalPaise)
        // Same idempotency key -> same ride, no second hold.
        assertEquals(ride.rideId, c.rides.request(quote.quoteId, quote.fare.totalPaise, "e2e-ride-1").ok().rideId)
        assertEquals(quote.fare.totalPaise, c.wallet.wallet().ok().heldPaise)
        assertEquals(ride.rideId, c.rides.active().ok()!!.rideId)

        val seen = mutableListOf<RideStatus>()
        var current = ride
        val deadline = System.currentTimeMillis() + 30_000
        while (!current.status.isTerminal && System.currentTimeMillis() < deadline) {
            if (seen.lastOrNull() != current.status) seen += current.status
            delay(100)
            current = c.rides.get(ride.rideId).ok()
        }
        seen += current.status
        assertEquals(RideStatus.COMPLETED, current.status)
        // Statuses observed by the client only ever move forward through valid transitions.
        seen.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", b.ordinal > a.ordinal) }
        assertEquals(quote.fare.totalPaise, current.chargedPaise) // DISPLAYED == CHARGED

        val w = c.wallet.wallet().ok()
        assertEquals(50_000L - quote.fare.totalPaise, w.balancePaise)
        assertEquals(0L, w.heldPaise)
        val types = c.wallet.transactions().ok().map { it.type }.toSet()
        assertEquals(setOf(LedgerType.TOPUP_CREDIT, LedgerType.RIDE_HOLD, LedgerType.RIDE_CAPTURE), types)

        // Rating once
        assertTrue(c.rides.rate(ride.rideId, 5, listOf("Polite Pilot")).ok().ratingStars == 5)
        assertTrue(c.rides.rate(ride.rideId, 1, emptyList()) is Outcome.Failure)

        // Another user cannot see this ride or wallet data.
        val other = Client(baseUrl).also { it.signIn("+919811100002") }
        assertEquals(404, ((other.rides.get(ride.rideId) as Outcome.Failure).error as AppError.Api).httpStatus)
        assertEquals(0L, other.wallet.wallet().ok().balancePaise)

        // SAFETY: no integration -> honest "unavailable", never a fake success.
        assertTrue(c.safety.triggerSos(30.3, 78.0, null).ok() is SosResult.Unavailable)
        // Masked calling not configured
        assertTrue((c.rides.requestMaskedCall(ride.rideId) as Outcome.Failure).error is AppError.NotConfigured)

        // LOGOUT revokes the session server-side.
        c.auth.logout().ok()
        assertTrue((c.wallet.wallet() as Outcome.Failure).error is AppError.Unauthorized)
    }

    @Test
    fun `cancelling before pickup releases the hold`() = runBlocking {
        val c = Client(baseUrl)
        c.signIn("+919811100003")
        val order = c.payments.createOrder(40_000, "e2e-c-1").ok()
        c.payments.verify(order, c.checkout.launch(order).ok()).ok()
        val a = LocationPoint("Paltan Bazaar", "", 30.3210, 78.0410)
        val b = LocationPoint("ISBT Dehradun", "", 30.2870, 78.0060)
        val quote = c.rides.quote("moto_electric", a, b).ok()
        val ride = c.rides.request(quote.quoteId, quote.fare.totalPaise, "e2e-c-ride").ok()
        val cancelled = c.rides.cancel(ride.rideId, "changed plans")
        // The simulator may already have moved the ride forward; either way the result is consistent.
        val final = c.rides.get(ride.rideId).ok()
        val w = c.wallet.wallet().ok()
        if (cancelled is Outcome.Success) {
            assertEquals(RideStatus.CANCELLED, final.status)
            assertEquals(40_000L, w.balancePaise)
            assertEquals(0L, w.heldPaise)
        } else {
            assertTrue(final.status == RideStatus.IN_PROGRESS || final.status == RideStatus.COMPLETED)
        }
    }

    @Test
    fun `insufficient balance is rejected by the server`() = runBlocking {
        val c = Client(baseUrl)
        c.signIn("+919811100004")
        val a = LocationPoint("Clock Tower", "", 30.3255, 78.0436)
        val b = LocationPoint("Airport", "", 30.1900, 78.1800)
        val quote = c.rides.quote("cab_luxury", a, b).ok()
        val r = c.rides.request(quote.quoteId, quote.fare.totalPaise, "e2e-poor")
        assertEquals("INSUFFICIENT_FUNDS", ((r as Outcome.Failure).error as AppError.Api).code)
    }
}
