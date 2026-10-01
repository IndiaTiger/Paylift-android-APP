package com.example.testing

import com.example.core.AppError
import com.example.core.Outcome
import com.example.domain.FareQuote
import com.example.domain.FleetCatalog
import com.example.domain.LocationPoint
import com.example.domain.RideStatus
import com.example.domain.FareCalculatorTest
import com.example.services.CheckoutResult
import com.example.services.LedgerEntry
import com.example.services.NotificationService
import com.example.services.PaymentCheckout
import com.example.services.PaymentOrder
import com.example.services.PaymentService
import com.example.services.PaymentStatus
import com.example.services.Ride
import com.example.services.RideMessage
import com.example.services.RideQuote
import com.example.services.RideService
import com.example.services.Route
import com.example.services.WalletBalance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic fake payment backend. Models the server rules the client relies on:
 * idempotent orders, credit only after a verified signature, never twice.
 */
class FakePaymentService : PaymentService {
    val orders = linkedMapOf<String, PaymentOrder>() // by idempotency key
    var balance = 0L
    val createCalls = AtomicInteger()
    val verifyCalls = AtomicInteger()
    var failVerifyWith: AppError? = null
    var statusOverride: PaymentStatus? = null

    override suspend fun createOrder(amountPaise: Long, idempotencyKey: String): Outcome<PaymentOrder> {
        createCalls.incrementAndGet()
        val o = orders.getOrPut(idempotencyKey) { PaymentOrder("pay_${orders.size + 1}", "test", "order_${orders.size + 1}", amountPaise, PaymentStatus.PENDING, "pk") }
        return Outcome.Success(o)
    }

    override suspend fun verify(order: PaymentOrder, result: CheckoutResult): Outcome<Pair<PaymentStatus, WalletBalance>> {
        verifyCalls.incrementAndGet()
        failVerifyWith?.let { return Outcome.Failure(it) }
        val key = orders.entries.first { it.value.paymentId == order.paymentId }.key
        val current = orders.getValue(key)
        val newStatus = when (result) {
            is CheckoutResult.Completed -> if (result.signature == "valid-signature") PaymentStatus.SUCCESS else return Outcome.Failure(AppError.Api(400, "INVALID_SIGNATURE", "bad signature"))
            is CheckoutResult.Failed -> PaymentStatus.FAILED
            CheckoutResult.Cancelled -> PaymentStatus.CANCELLED
        }
        if (current.status == PaymentStatus.PENDING) {
            orders[key] = current.copy(status = newStatus)
            if (newStatus == PaymentStatus.SUCCESS) balance += current.amountPaise
        }
        return Outcome.Success(orders.getValue(key).status to wallet())
    }

    override suspend fun status(paymentId: String): Outcome<PaymentStatus> =
        Outcome.Success(statusOverride ?: orders.values.first { it.paymentId == paymentId }.status)

    fun wallet() = WalletBalance(balance, 0, balance, "INR")
}

class FakeCheckout(var next: () -> Outcome<CheckoutResult> = { Outcome.Success(CheckoutResult.Completed("pp_1", "valid-signature")) }) : PaymentCheckout {
    override val providerLabel = "Fake Gateway"
    override var isAvailable = true
    override suspend fun launch(order: PaymentOrder) = next()
}

fun sampleQuote(id: String = "qt_1", expiresAt: Long = Long.MAX_VALUE): RideQuote {
    val a = LocationPoint("Clock Tower", "", 30.3255, 78.0436)
    val b = LocationPoint("Rajpur Road", "", 30.3580, 78.0720)
    return RideQuote(id, "cab_mini", a, b, FareCalculatorTest.quoteFor(FleetCatalog.VEHICLES[3], 5200, 16), Route(5200, 16, listOf(30.3255 to 78.0436, 30.358 to 78.072), "test"), expiresAt)
}

fun sampleRide(id: String = "ride_1", status: RideStatus = RideStatus.SEARCHING, version: Long = 1, fare: FareQuote = sampleQuote().fare) = Ride(
    rideId = id, status = status, version = version, vehicleId = "cab_mini",
    pickup = LocationPoint("Clock Tower", "", 30.3255, 78.0436), dropoff = LocationPoint("Rajpur Road", "", 30.3580, 78.0720),
    distanceMeters = 5200, durationMinutes = 16, polyline = emptyList(), fare = fare,
    chargedPaise = if (status == RideStatus.COMPLETED) fare.totalPaise else 0, startOtp = "1234", pilot = null,
    progressFraction = 0.0, ratingStars = null, ratingTags = null, createdAt = 1_700_000_000_000,
)

/** Fake ride backend: idempotent on the key, at most one active ride. */
class FakeRideService : RideService {
    val requestsByKey = linkedMapOf<String, Ride>()
    val requestCalls = AtomicInteger()
    var failNextRequestWith: AppError? = null
    var active: Ride? = null

    override suspend fun quote(vehicleId: String, pickup: LocationPoint, dropoff: LocationPoint) = Outcome.Success(sampleQuote())
    override suspend fun request(quoteId: String, expectedTotalPaise: Long, idempotencyKey: String): Outcome<Ride> {
        requestCalls.incrementAndGet()
        requestsByKey[idempotencyKey]?.let { return Outcome.Success(it) }
        if (active != null) return Outcome.Failure(AppError.Api(409, "ACTIVE_RIDE_EXISTS", "You already have an active ride"))
        // The server processes the request even when the client later sees a timeout.
        val ride = sampleRide("ride_${requestsByKey.size + 1}")
        requestsByKey[idempotencyKey] = ride
        active = ride
        failNextRequestWith?.let { failNextRequestWith = null; return Outcome.Failure(it) }
        return Outcome.Success(ride)
    }
    override suspend fun get(rideId: String) = Outcome.Success(requestsByKey.values.first { it.rideId == rideId })
    override suspend fun active(): Outcome<Ride?> = Outcome.Success(active)
    override suspend fun history(limit: Int): Outcome<List<Ride>> = Outcome.Success(requestsByKey.values.toList())
    override suspend fun cancel(rideId: String, reason: String?): Outcome<Ride> {
        val r = requestsByKey.values.first { it.rideId == rideId }
        if (!r.status.riderCanCancel) return Outcome.Failure(AppError.Api(409, "INVALID_TRANSITION", "no"))
        val c = r.copy(status = RideStatus.CANCELLED, version = r.version + 1)
        requestsByKey.entries.first { it.value.rideId == rideId }.setValue(c)
        active = null
        return Outcome.Success(c)
    }
    override suspend fun rate(rideId: String, stars: Int, tags: List<String>) = get(rideId)
    override suspend fun messages(rideId: String): Outcome<List<RideMessage>> = Outcome.Success(emptyList())
    override suspend fun sendMessage(rideId: String, body: String) = Outcome.Success(RideMessage("m", "rider", body, 0))
    override suspend fun requestMaskedCall(rideId: String): Outcome<Unit> = Outcome.Failure(AppError.NotConfigured("CALL_PROVIDER", "not configured"))
}

class StaticNotifications(private val rides: RideService) : NotificationService {
    override fun rideUpdates(rideId: String): Flow<Outcome<Ride>> = flowOf()
}

fun ledgerEntry(id: String, type: com.example.services.LedgerType, amount: Long) =
    LedgerEntry(id, type, amount, amount, 0, "ref:$id", null, null, type.name, 1_700_000_000_000)
