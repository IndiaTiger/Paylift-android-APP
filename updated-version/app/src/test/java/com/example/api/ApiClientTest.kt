package com.example.api

import com.example.auth.InMemoryTokenStore
import com.example.auth.Tokens
import com.example.core.AppError
import com.example.core.Outcome
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ApiClientTest {
    private lateinit var server: MockWebServer
    private val tokens = InMemoryTokenStore(Tokens("access-1", "refresh-1"))
    private lateinit var client: ApiClient

    private val walletJson = """{"balancePaise":1000,"heldPaise":0,"availablePaise":1000,"currency":"INR"}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = ApiClient(server.url("/").toString(), tokens, timeoutSeconds = 2)
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `sends bearer token and parses response`() = runBlocking {
        server.enqueue(MockResponse().setBody(walletJson))
        val r = client.call { wallet() }
        assertEquals(1000L, (r as Outcome.Success).value.balancePaise)
        assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `401 triggers a single refresh then retries with the new token`() = runBlocking {
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/auth/refresh" -> {
                    refreshes.incrementAndGet()
                    Thread.sleep(100)
                    MockResponse().setBody("""{"accessToken":"access-2","refreshToken":"refresh-2","expiresInSec":900}""")
                }
                request.getHeader("Authorization") == "Bearer access-2" -> MockResponse().setBody(walletJson)
                else -> MockResponse().setResponseCode(401).setBody("""{"error":{"code":"UNAUTHENTICATED","message":"expired"}}""")
            }
        }
        // Five concurrent requests hit 401 at once -> exactly one refresh (rotating tokens must not race).
        val results = (1..5).map { async(kotlinx.coroutines.Dispatchers.IO) { client.call { wallet() } } }.awaitAll()
        results.forEach { assertTrue(it is Outcome.Success) }
        assertEquals(1, refreshes.get())
        assertEquals(Tokens("access-2", "refresh-2"), tokens.read())
    }

    @Test
    fun `failed refresh clears the session and signals expiry`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":"REFRESH_TOKEN_REUSED","message":"reused"}}""")
        }
        val expired = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { withTimeout(5000) { client.sessionExpired.first() } }
        val r = client.call { wallet() }
        assertEquals(AppError.Unauthorized, (r as Outcome.Failure).error)
        expired.await()
        assertNull(tokens.read())
    }

    @Test
    fun `structured errors are mapped, never swallowed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"INSUFFICIENT_FUNDS","message":"Insufficient wallet balance"}}"""))
        val e = (client.call { wallet() } as Outcome.Failure).error as AppError.Api
        assertEquals("INSUFFICIENT_FUNDS", e.code)
        assertEquals(409, e.httpStatus)

        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"PAYMENT_PROVIDER_NOT_CONFIGURED","message":"Payment provider is not configured"}}"""))
        assertTrue((client.call { wallet() } as Outcome.Failure).error is AppError.NotConfigured)

        server.enqueue(MockResponse().setResponseCode(500).setBody("not json"))
        val e500 = (client.call { wallet() } as Outcome.Failure).error
        assertTrue(e500 is AppError.Api && e500.isRetryable)
    }

    @Test
    fun `timeouts and malformed bodies are distinct failures`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(AppError.Timeout, (client.call { wallet() } as Outcome.Failure).error)
        server.enqueue(MockResponse().setBody("""{"balancePaise":"lots"}"""))
        assertTrue((client.call { wallet() } as Outcome.Failure).error is AppError.Unexpected)
    }

    @Test
    fun `connection failure maps to Offline`() = runBlocking {
        server.shutdown()
        assertEquals(AppError.Offline, (client.call { wallet() } as Outcome.Failure).error)
    }

    @Test
    fun `unconfigured backend never makes a request and reports NotConfigured`() = runBlocking {
        val unconfigured = ApiClient("", tokens)
        val r = unconfigured.call { wallet() }
        assertTrue((r as Outcome.Failure).error is AppError.NotConfigured)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `idempotency key header is sent for ride requests and payment orders`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"X","message":"x"}}"""))
        client.call { createPaymentOrder("key-123", com.example.api.dto.CreateOrderBody(500)) }
        assertEquals("key-123", server.takeRequest().getHeader("Idempotency-Key"))
    }
}
