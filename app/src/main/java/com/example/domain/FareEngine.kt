package com.example.domain

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
    // Transparency split
    val platformCutPercent: Double = 15.0,
    val pilotCutPercent: Double = 85.0,
    val platformCutAmount: Double,
    val pilotTakeHomeEarnings: Double
)

object FareEngine {

    /**
     * Authoritative calculation according to PayLift multidimensional formula:
     * Final Fare = Base Starting Fare + (Distance km × Per-Km Rate) + (Duration min × Time Rate)
     *              × Category Multiplier × Luxury Multiplier × Engine CC Band Factor
     *              + Platform Safety Fee + GST (5%).
     *
     * Net pilot earnings = 85% of net ride revenue, platform commission = 15%.
     */
    fun calculateFare(
        vehicle: VehicleOption,
        distanceKm: Double,
        durationMinutes: Int
    ): FareBreakdown {
        val base = vehicle.baseFare
        val distCharge = distanceKm * vehicle.perKmRate
        val timeCharge = durationMinutes * vehicle.timeRatePerMin

        val combinedRateFactor = vehicle.categoryMultiplier * vehicle.luxuryMultiplier * vehicle.engineCcFactor

        // (Distance charge + Time charge) scaled by vehicle capability band factors
        val dynamicTransitCharge = (distCharge + timeCharge) * combinedRateFactor
        val subtotalPreFees = base + dynamicTransitCharge

        val safetyFee = vehicle.platformSafetyFee

        // 5% GST (Standard Indian mobility GST on aggregator rides)
        val gstTax = Math.round((subtotalPreFees + safetyFee) * 0.05 * 100.0) / 100.0

        val grossFinal = Math.round((subtotalPreFees + safetyFee + gstTax) * 10.0) / 10.0

        // Pilot earnings is calculated on net ride service (gross minus GST)
        val netRideRevenue = (subtotalPreFees + safetyFee)
        val pilotEarnings = Math.round(netRideRevenue * 0.85 * 10.0) / 10.0
        val platformCut = Math.round(netRideRevenue * 0.15 * 10.0) / 10.0

        return FareBreakdown(
            baseFare = base,
            distanceCharge = Math.round(distCharge * 10.0) / 10.0,
            durationCharge = Math.round(timeCharge * 10.0) / 10.0,
            categoryMultiplier = vehicle.categoryMultiplier,
            luxuryMultiplier = vehicle.luxuryMultiplier,
            engineCcFactor = vehicle.engineCcFactor,
            subtotalBeforeMultipliers = Math.round((base + distCharge + timeCharge) * 10.0) / 10.0,
            totalMultipliedRideFare = Math.round(subtotalPreFees * 10.0) / 10.0,
            platformSafetyFee = safetyFee,
            gstTax = gstTax,
            grossFinalFare = grossFinal,
            platformCutPercent = 15.0,
            pilotCutPercent = 85.0,
            platformCutAmount = platformCut,
            pilotTakeHomeEarnings = pilotEarnings
        )
    }
}
