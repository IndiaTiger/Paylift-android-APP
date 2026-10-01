package com.example.domain

/**
 * Fare breakdown as displayed by the UI.
 *
 * Built exclusively from a backend quote ([FareQuote]); the Double fields are display
 * conversions of the quote's integer paise. [totalPaise] is exactly the amount the backend holds
 * at request time and captures at completion, so DISPLAYED == CHARGED by construction.
 */
data class FareBreakdown(
    val baseFare: Double,
    val distanceCharge: Double,
    val durationCharge: Double,
    val categoryMultiplier: Double,
    val luxuryMultiplier: Double,
    val engineCcFactor: Double,
    val subtotalBeforeMultipliers: Double,
    val totalMultipliedRideFare: Double,
    val platformSafetyFee: Double,
    val gstTax: Double,
    val grossFinalFare: Double,
    val platformCutPercent: Double = 15.0,
    val pilotCutPercent: Double = 85.0,
    val platformCutAmount: Double,
    val pilotTakeHomeEarnings: Double,
    /** Authoritative total in paise (the quoted, held and charged amount). */
    val totalPaise: Long,
)

/** Server fare quote, integer paise / basis points (mirrors the backend `fare` object). */
data class FareQuote(
    val vehicleId: String,
    val distanceMeters: Long,
    val durationMinutes: Long,
    val baseFarePaise: Long,
    val perKmRatePaise: Long,
    val perMinRatePaise: Long,
    val distanceChargePaise: Long,
    val durationChargePaise: Long,
    val categoryMultiplierBp: Long,
    val luxuryMultiplierBp: Long,
    val engineCcFactorBp: Long,
    val transitChargePaise: Long,
    val subtotalPaise: Long,
    val platformSafetyFeePaise: Long,
    val taxablePaise: Long,
    val gstPaise: Long,
    val totalPaise: Long,
    val pilotEarningPaise: Long,
    val platformCommissionPaise: Long,
) {
    /**
     * Arithmetic consistency of a quote received from the network. A quote failing this check is
     * never displayed or used to request a ride.
     */
    fun isConsistent(): Boolean =
        totalPaise > 0 &&
            subtotalPaise == baseFarePaise + transitChargePaise &&
            taxablePaise == subtotalPaise + platformSafetyFeePaise &&
            totalPaise == taxablePaise + gstPaise &&
            pilotEarningPaise + platformCommissionPaise == taxablePaise &&
            gstPaise == FareCalculator.roundDiv(taxablePaise * FareCalculator.GST_PERCENT, 100)

    fun toBreakdown(): FareBreakdown {
        fun r(p: Long) = p / 100.0
        fun m(bp: Long) = bp / 10_000.0
        return FareBreakdown(
            baseFare = r(baseFarePaise),
            distanceCharge = r(distanceChargePaise),
            durationCharge = r(durationChargePaise),
            categoryMultiplier = m(categoryMultiplierBp),
            luxuryMultiplier = m(luxuryMultiplierBp),
            engineCcFactor = m(engineCcFactorBp),
            subtotalBeforeMultipliers = r(baseFarePaise + distanceChargePaise + durationChargePaise),
            totalMultipliedRideFare = r(subtotalPaise),
            platformSafetyFee = r(platformSafetyFeePaise),
            gstTax = r(gstPaise),
            grossFinalFare = r(totalPaise),
            platformCutAmount = r(platformCommissionPaise),
            pilotTakeHomeEarnings = r(pilotEarningPaise),
            totalPaise = totalPaise,
        )
    }
}
