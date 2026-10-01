package com.example.domain

enum class VehicleTypeGroup {
    TWO_WHEELER,
    FOUR_WHEELER
}

data class VehicleOption(
    val id: String,
    val name: String,
    val categoryName: String, // "Standard Commuter", "Premium Sports", "Electric Scooter", etc.
    val group: VehicleTypeGroup,
    val modelsExample: String, // "Hero Splendor / Honda Shine", "Yamaha R15 / RE 350", "Ola S1 / Ather 450X"
    val capacity: String, // "1 Rider (Helmet included)", "4 Seats", "6-7 Seats"
    // Exact rates: paise and basis points (x10000). These mirror backend/src/fare.js; the fare the
    // rider is charged always comes from the backend quote.
    val baseFarePaise: Long,
    val perKmRatePaise: Long,
    val timeRatePerMinPaise: Long,
    val categoryMultiplierBp: Long,
    val luxuryMultiplierBp: Long,
    val engineCcFactorBp: Long,
    val platformSafetyFeePaise: Long,
    /** Minutes until the nearest available pilot, from GET /pilots/nearby. Null = unknown / none nearby. */
    val etaMins: Int? = null,
    val tag: String? = null // "Most Popular", "Eco EV", "Value", "Executive"
) {
    // Display-only conversions.
    val perKmRate: Double get() = perKmRatePaise / 100.0
    val timeRatePerMin: Double get() = timeRatePerMinPaise / 100.0
}

object FleetCatalog {

    val VEHICLES = listOf(
        // Motorcycles & Scooters
        VehicleOption(
            id = "moto_commuter",
            name = "Moto Commuter",
            categoryName = "Standard Commuter (100-125cc)",
            group = VehicleTypeGroup.TWO_WHEELER,
            modelsExample = "Hero Splendor, Honda Shine, TVS Raider",
            capacity = "1 Rider (Helmet provided)",
            baseFarePaise = 2000,
            perKmRatePaise = 850,
            timeRatePerMinPaise = 80,
            categoryMultiplierBp = 10000,
            luxuryMultiplierBp = 10000,
            engineCcFactorBp = 10000,
            platformSafetyFeePaise = 500,
            tag = "Fastest"
        ),
        VehicleOption(
            id = "moto_sports",
            name = "Moto Sports & Cruise",
            categoryName = "Premium Sports (150-350cc)",
            group = VehicleTypeGroup.TWO_WHEELER,
            modelsExample = "Royal Enfield Classic 350, Yamaha R15",
            capacity = "1 Rider (Premium Gear)",
            baseFarePaise = 3500,
            perKmRatePaise = 1200,
            timeRatePerMinPaise = 120,
            categoryMultiplierBp = 12000,
            luxuryMultiplierBp = 12000,
            engineCcFactorBp = 13500,
            platformSafetyFeePaise = 800,
            tag = "350cc Heavy"
        ),
        VehicleOption(
            id = "moto_electric",
            name = "Electric Green Scooter",
            categoryName = "Electric Scooters (EV)",
            group = VehicleTypeGroup.TWO_WHEELER,
            modelsExample = "Ola S1 Pro, Ather 450X, Chetak EV",
            capacity = "1 Rider (Zero Emission)",
            baseFarePaise = 2500,
            perKmRatePaise = 900,
            timeRatePerMinPaise = 90,
            categoryMultiplierBp = 10500,
            luxuryMultiplierBp = 10000,
            engineCcFactorBp = 10500,
            platformSafetyFeePaise = 600,
            tag = "100% Green"
        ),

        // Cars & Cabs
        VehicleOption(
            id = "cab_mini",
            name = "PayLift Mini",
            categoryName = "Mini / Hatchback",
            group = VehicleTypeGroup.FOUR_WHEELER,
            modelsExample = "Maruti Suzuki WagonR, Swift, Celerio",
            capacity = "4 Seats (AC)",
            baseFarePaise = 5000,
            perKmRatePaise = 1400,
            timeRatePerMinPaise = 160,
            categoryMultiplierBp = 12500,
            luxuryMultiplierBp = 10000,
            engineCcFactorBp = 10000,
            platformSafetyFeePaise = 1200,
            tag = "Most Economical"
        ),
        VehicleOption(
            id = "cab_sedan",
            name = "Prime Sedan",
            categoryName = "Prime Sedan",
            group = VehicleTypeGroup.FOUR_WHEELER,
            modelsExample = "Swift Dzire, Honda City, Hyundai Aura",
            capacity = "4 Seats (Extra Legroom + AC)",
            baseFarePaise = 7000,
            perKmRatePaise = 1750,
            timeRatePerMinPaise = 200,
            categoryMultiplierBp = 14500,
            luxuryMultiplierBp = 12000,
            engineCcFactorBp = 11500,
            platformSafetyFeePaise = 1500,
            tag = "Top Rated"
        ),
        VehicleOption(
            id = "cab_suv",
            name = "PayLift SUV Max",
            categoryName = "Premium SUV (7-Seater)",
            group = VehicleTypeGroup.FOUR_WHEELER,
            modelsExample = "Toyota Innova Crysta, Mahindra Scorpio-N",
            capacity = "6-7 Seats (Dual AC, Hill Certified)",
            baseFarePaise = 11000,
            perKmRatePaise = 2400,
            timeRatePerMinPaise = 280,
            categoryMultiplierBp = 18000,
            luxuryMultiplierBp = 14000,
            engineCcFactorBp = 14000,
            platformSafetyFeePaise = 2000,
            tag = "Spacious"
        ),
        VehicleOption(
            id = "cab_luxury",
            name = "Executive Black",
            categoryName = "High-end Luxury / Executive",
            group = VehicleTypeGroup.FOUR_WHEELER,
            modelsExample = "Mercedes-Benz E-Class, BMW 3 Series, Audi A4",
            capacity = "4 Seats (VIP Chauffeur & Refreshments)",
            baseFarePaise = 28000,
            perKmRatePaise = 4500,
            timeRatePerMinPaise = 500,
            categoryMultiplierBp = 24000,
            luxuryMultiplierBp = 20000,
            engineCcFactorBp = 16000,
            platformSafetyFeePaise = 4500,
            tag = "Luxury VIP"
        )
    )
}
