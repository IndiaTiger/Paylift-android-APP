package com.example.domain

/**
 * Integer-paise mirror of the server fare engine (backend/src/fare.js).
 *
 * The backend quote is authoritative: the app displays and charges the server's quote. This
 * mirror exists so both implementations can be pinned to the same contract vectors
 * (contracts/fare-test-vectors.json) and so the app can validate that a quote it received is
 * internally consistent (components sum to the total) before showing it.
 */
object FareCalculator {
    private const val BP = 10_000L
    const val GST_PERCENT = 5L
    const val PILOT_PERCENT = 85L
    const val MIN_DISTANCE_M = 200L
    const val MAX_DISTANCE_M = 150_000L
    const val MAX_DURATION_MIN = 600L

    data class Rates(
        val baseFarePaise: Long,
        val perKmPaise: Long,
        val perMinPaise: Long,
        val categoryBp: Long,
        val luxuryBp: Long,
        val engineCcBp: Long,
        val safetyFeePaise: Long,
    )

    data class Result(
        val distanceChargePaise: Long,
        val durationChargePaise: Long,
        val combinedFactorBp: Long,
        val transitChargePaise: Long,
        val subtotalPaise: Long,
        val taxablePaise: Long,
        val gstPaise: Long,
        val totalPaise: Long,
        val pilotEarningPaise: Long,
        val platformCommissionPaise: Long,
    )

    /** Half-up integer division for non-negative operands. */
    fun roundDiv(numerator: Long, denominator: Long): Long {
        require(numerator >= 0 && denominator > 0)
        return Math.floorDiv(Math.addExact(Math.multiplyExact(2L, numerator), denominator), 2L * denominator)
    }

    fun calculate(rates: Rates, distanceMeters: Long, durationMinutes: Long): Result {
        require(distanceMeters in MIN_DISTANCE_M..MAX_DISTANCE_M) { "distance out of range: $distanceMeters" }
        require(durationMinutes in 1..MAX_DURATION_MIN) { "duration out of range: $durationMinutes" }
        val distance = roundDiv(Math.multiplyExact(distanceMeters, rates.perKmPaise), 1000)
        val time = Math.multiplyExact(durationMinutes, rates.perMinPaise)
        val factor = roundDiv(roundDiv(rates.categoryBp * rates.luxuryBp, BP) * rates.engineCcBp, BP)
        val transit = roundDiv(Math.multiplyExact(distance + time, factor), BP)
        val subtotal = rates.baseFarePaise + transit
        val taxable = subtotal + rates.safetyFeePaise
        val gst = roundDiv(taxable * GST_PERCENT, 100)
        val pilot = roundDiv(taxable * PILOT_PERCENT, 100)
        return Result(
            distanceChargePaise = distance,
            durationChargePaise = time,
            combinedFactorBp = factor,
            transitChargePaise = transit,
            subtotalPaise = subtotal,
            taxablePaise = taxable,
            gstPaise = gst,
            totalPaise = taxable + gst,
            pilotEarningPaise = pilot,
            platformCommissionPaise = taxable - pilot,
        )
    }

    /** Rates of a catalog vehicle, converted exactly to paise / basis points. */
    fun ratesFor(v: VehicleOption): Rates = Rates(
        baseFarePaise = v.baseFarePaise,
        perKmPaise = v.perKmRatePaise,
        perMinPaise = v.timeRatePerMinPaise,
        categoryBp = v.categoryMultiplierBp,
        luxuryBp = v.luxuryMultiplierBp,
        engineCcBp = v.engineCcFactorBp,
        safetyFeePaise = v.platformSafetyFeePaise,
    )
}
