package com.example.domain

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FareCalculatorTest {

    private fun vectorsFile(): File =
        listOf(File("../contracts/fare-test-vectors.json"), File("contracts/fare-test-vectors.json")).first { it.exists() }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `mirror matches every server contract vector exactly`() {
        val type = Types.newParameterizedType(List::class.java, Map::class.java)
        val vectors = Moshi.Builder().build().adapter<List<Map<String, Any>>>(type).fromJson(vectorsFile().readText())!!
        assertTrue(vectors.size >= 20)
        for (v in vectors) {
            val vehicle = FleetCatalog.VEHICLES.first { it.id == v["vehicleId"] }
            val expected = v["expected"] as Map<String, Any>
            fun e(k: String) = (expected[k] as Double).toLong()
            val r = FareCalculator.calculate(
                FareCalculator.ratesFor(vehicle),
                (v["distanceMeters"] as Double).toLong(),
                (v["durationMinutes"] as Double).toLong(),
            )
            val ctx = "vector $v"
            assertEquals(ctx, e("baseFarePaise"), vehicle.baseFarePaise)
            assertEquals(ctx, e("distanceChargePaise"), r.distanceChargePaise)
            assertEquals(ctx, e("durationChargePaise"), r.durationChargePaise)
            assertEquals(ctx, e("combinedFactorBp"), r.combinedFactorBp)
            assertEquals(ctx, e("transitChargePaise"), r.transitChargePaise)
            assertEquals(ctx, e("subtotalPaise"), r.subtotalPaise)
            assertEquals(ctx, e("taxablePaise"), r.taxablePaise)
            assertEquals(ctx, e("gstPaise"), r.gstPaise)
            assertEquals(ctx, e("totalPaise"), r.totalPaise)
            assertEquals(ctx, e("pilotEarningPaise"), r.pilotEarningPaise)
            assertEquals(ctx, e("platformCommissionPaise"), r.platformCommissionPaise)
        }
    }

    @Test
    fun `hand-checked PayLift Mini fare (5 2 km, 16 min) equals original prototype fare`() {
        val r = FareCalculator.calculate(FareCalculator.ratesFor(FleetCatalog.VEHICLES[3]), 5200, 16)
        // 72.80 + 25.60 = 98.40 x 1.25 = 123.00; + 50 base + 12 fee = 185.00; GST 9.25 -> 194.25
        assertEquals(19425, r.totalPaise)
        assertEquals(925, r.gstPaise)
        assertEquals(15725, r.pilotEarningPaise)
        assertEquals(2775, r.platformCommissionPaise)
    }

    @Test
    fun `rounding is half-up and deterministic`() {
        assertEquals(3, FareCalculator.roundDiv(5, 2))
        assertEquals(2, FareCalculator.roundDiv(7, 4)) // 1.75 -> 2
        assertEquals(1, FareCalculator.roundDiv(5, 4)) // 1.25 -> 1
        assertEquals(0, FareCalculator.roundDiv(0, 7))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `distance below minimum is rejected`() {
        FareCalculator.calculate(FareCalculator.ratesFor(FleetCatalog.VEHICLES[0]), 50, 5)
    }

    @Test
    fun `quote consistency check rejects tampered totals`() {
        val q = quoteFor(FleetCatalog.VEHICLES[3], 5200, 16)
        assertTrue(q.isConsistent())
        assertFalse(q.copy(totalPaise = q.totalPaise - 1).isConsistent())
        assertFalse(q.copy(pilotEarningPaise = q.pilotEarningPaise + 1).isConsistent())
        assertFalse(q.copy(gstPaise = 0, totalPaise = q.taxablePaise).isConsistent())
        val b = q.toBreakdown()
        assertEquals(194.25, b.grossFinalFare, 0.0)
        assertEquals(q.totalPaise, b.totalPaise)
    }

    companion object {
        fun quoteFor(v: VehicleOption, meters: Long, minutes: Long): FareQuote {
            val r = FareCalculator.calculate(FareCalculator.ratesFor(v), meters, minutes)
            return FareQuote(
                v.id, meters, minutes, v.baseFarePaise, v.perKmRatePaise, v.timeRatePerMinPaise, r.distanceChargePaise,
                r.durationChargePaise, v.categoryMultiplierBp, v.luxuryMultiplierBp, v.engineCcFactorBp, r.transitChargePaise,
                r.subtotalPaise, v.platformSafetyFeePaise, r.taxablePaise, r.gstPaise, r.totalPaise, r.pilotEarningPaise,
                r.platformCommissionPaise,
            )
        }
    }
}

class MoneyTest {
    @Test
    fun `rupee parsing is exact without floating point`() {
        assertEquals(25000L, Paise.parseRupees("250")?.value)
        assertEquals(9950L, Paise.parseRupees("99.5")?.value)
        assertEquals(9905L, Paise.parseRupees("99.05")?.value)
        assertEquals(1L, Paise.parseRupees("0.01")?.value)
        assertNull(Paise.parseRupees("1.005"))
        assertNull(Paise.parseRupees("-5"))
        assertNull(Paise.parseRupees("abc"))
        assertNull(Paise.parseRupees(""))
        assertNull(Paise.parseRupees("1e5"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative money cannot exist`() {
        Paise(-1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `subtraction below zero throws`() {
        Paise(5) - Paise(6)
    }
}

class RideStatusTest {
    @Test
    fun `terminal states have no outgoing transitions`() {
        for (from in RideStatus.entries.filter { it.isTerminal }) {
            for (to in RideStatus.entries) assertFalse("$from -> $to", RideStatus.canTransition(from, to))
        }
    }

    @Test
    fun `guards against invalid ride transitions`() {
        assertFalse(RideStatus.canTransition(RideStatus.COMPLETED, RideStatus.CANCELLED)) // cancel after completion
        assertFalse(RideStatus.canTransition(RideStatus.CANCELLED, RideStatus.COMPLETED)) // completion after cancel
        assertFalse(RideStatus.canTransition(RideStatus.COMPLETED, RideStatus.COMPLETED)) // double completion
        assertFalse(RideStatus.canTransition(RideStatus.IN_PROGRESS, RideStatus.CANCELLED))
        assertFalse(RideStatus.canTransition(RideStatus.ARRIVED, RideStatus.IN_PROGRESS)) // OTP required first
        assertTrue(RideStatus.canTransition(RideStatus.OTP_VERIFIED, RideStatus.IN_PROGRESS))
        assertTrue(RideStatus.canTransition(RideStatus.SEARCHING, RideStatus.NO_PILOT_FOUND))
    }

    @Test
    fun `happy path is a valid chain`() {
        val chain = listOf(
            RideStatus.REQUESTED, RideStatus.SEARCHING, RideStatus.ASSIGNED, RideStatus.PILOT_ACCEPTED,
            RideStatus.PILOT_ARRIVING, RideStatus.ARRIVED, RideStatus.OTP_VERIFIED, RideStatus.IN_PROGRESS, RideStatus.COMPLETED,
        )
        chain.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", RideStatus.canTransition(a, b)) }
    }

    @Test
    fun `unknown server status degrades to FAILED instead of crashing`() {
        assertEquals(RideStatus.FAILED, RideStatus.parse("TELEPORTED"))
        assertEquals(RideStatus.ARRIVED, RideStatus.parse("ARRIVED"))
    }

    @Test
    fun `rider cancellation allowed only before the trip starts`() {
        assertTrue(RideStatus.SEARCHING.riderCanCancel)
        assertTrue(RideStatus.ARRIVED.riderCanCancel)
        assertFalse(RideStatus.IN_PROGRESS.riderCanCancel)
        assertFalse(RideStatus.COMPLETED.riderCanCancel)
    }

    @Test
    fun `client transition table matches the server table`() {
        // Parse backend/src/rides.js TRANSITIONS so the two tables cannot drift apart.
        val js = listOf(File("../backend/src/rides.js"), File("backend/src/rides.js")).first { it.exists() }.readText()
        val block = js.substringAfter("const TRANSITIONS = {").substringBefore("};")
        val server = Regex("""\[S\.(\w+)]:\s*\[([^\]]*)]""").findAll(block).associate { m ->
            RideStatus.valueOf(m.groupValues[1]) to Regex("""S\.(\w+)""").findAll(m.groupValues[2]).map { RideStatus.valueOf(it.groupValues[1]) }.toSet()
        }
        assertEquals(server, RideStatus.TRANSITIONS)
    }
}

class LocationValidationTest {
    private val a = LocationPoint("A", "", 30.3255, 78.0436)
    private val b = LocationPoint("B", "", 30.3580, 78.0720)

    @Test
    fun `valid trip`() = assertEquals(TripValidation.Valid, LocationValidation.validateTrip(a, b))

    @Test
    fun `pickup equal to drop-off is invalid`() {
        assertTrue(LocationValidation.validateTrip(a, a.copy(name = "A2")) is TripValidation.Invalid)
    }

    @Test
    fun `unselected or impossible coordinates are invalid`() {
        assertTrue(LocationValidation.validateTrip(LocationPoint.unselected("x"), b) is TripValidation.Invalid)
        assertTrue(LocationValidation.validateTrip(a, b.copy(latitude = 91.0)) is TripValidation.Invalid)
        assertTrue(LocationValidation.validateTrip(a, b.copy(latitude = Double.NaN)) is TripValidation.Invalid)
        assertFalse(LocationValidation.isValidCoordinate(0.0, 0.0))
    }
}
