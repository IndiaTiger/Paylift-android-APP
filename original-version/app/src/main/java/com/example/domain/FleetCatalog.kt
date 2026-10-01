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
    val baseFare: Double,
    val perKmRate: Double,
    val timeRatePerMin: Double,
    val categoryMultiplier: Double,
    val luxuryMultiplier: Double,
    val engineCcFactor: Double,
    val platformSafetyFee: Double,
    val etaMins: Int,
    val tag: String? = null // "Most Popular", "Eco EV", "Value", "Executive"
)

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
            baseFare = 20.0,
            perKmRate = 8.5,
            timeRatePerMin = 0.8,
            categoryMultiplier = 1.0,
            luxuryMultiplier = 1.0,
            engineCcFactor = 1.0,
            platformSafetyFee = 5.0,
            etaMins = 2,
            tag = "Fastest"
        ),
        VehicleOption(
            id = "moto_sports",
            name = "Moto Sports & Cruise",
            categoryName = "Premium Sports (150-350cc)",
            group = VehicleTypeGroup.TWO_WHEELER,
            modelsExample = "Royal Enfield Classic 350, Yamaha R15",
            capacity = "1 Rider (Premium Gear)",
            baseFare = 35.0,
            perKmRate = 12.0,
            timeRatePerMin = 1.2,
            categoryMultiplier = 1.2,
            luxuryMultiplier = 1.2,
            engineCcFactor = 1.35,
            platformSafetyFee = 8.0,
            etaMins = 4,
            tag = "350cc Heavy"
        ),
        VehicleOption(
            id = "moto_electric",
            name = "Electric Green Scooter",
            categoryName = "Electric Scooters (EV)",
            group = VehicleTypeGroup.TWO_WHEELER,
            modelsExample = "Ola S1 Pro, Ather 450X, Chetak EV",
            capacity = "1 Rider (Zero Emission)",
            baseFare = 25.0,
            perKmRate = 9.0,
            timeRatePerMin = 0.9,
            categoryMultiplier = 1.05,
            luxuryMultiplier = 1.0,
            engineCcFactor = 1.05,
            platformSafetyFee = 6.0,
            etaMins = 3,
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
            baseFare = 50.0,
            perKmRate = 14.0,
            timeRatePerMin = 1.6,
            categoryMultiplier = 1.25,
            luxuryMultiplier = 1.0,
            engineCcFactor = 1.0,
            platformSafetyFee = 12.0,
            etaMins = 3,
            tag = "Most Economical"
        ),
        VehicleOption(
            id = "cab_sedan",
            name = "Prime Sedan",
            categoryName = "Prime Sedan",
            group = VehicleTypeGroup.FOUR_WHEELER,
            modelsExample = "Swift Dzire, Honda City, Hyundai Aura",
            capacity = "4 Seats (Extra Legroom + AC)",
            baseFare = 70.0,
            perKmRate = 17.5,
            timeRatePerMin = 2.0,
            categoryMultiplier = 1.45,
            luxuryMultiplier = 1.2,
            engineCcFactor = 1.15,
            platformSafetyFee = 15.0,
            etaMins = 4,
            tag = "Top Rated"
        ),
        VehicleOption(
            id = "cab_suv",
            name = "PayLift SUV Max",
            categoryName = "Premium SUV (7-Seater)",
            group = VehicleTypeGroup.FOUR_WHEELER,
            modelsExample = "Toyota Innova Crysta, Mahindra Scorpio-N",
            capacity = "6-7 Seats (Dual AC, Hill Certified)",
            baseFare = 110.0,
            perKmRate = 24.0,
            timeRatePerMin = 2.8,
            categoryMultiplier = 1.8,
            luxuryMultiplier = 1.4,
            engineCcFactor = 1.4,
            platformSafetyFee = 20.0,
            etaMins = 6,
            tag = "Spacious"
        ),
        VehicleOption(
            id = "cab_luxury",
            name = "Executive Black",
            categoryName = "High-end Luxury / Executive",
            group = VehicleTypeGroup.FOUR_WHEELER,
            modelsExample = "Mercedes-Benz E-Class, BMW 3 Series, Audi A4",
            capacity = "4 Seats (VIP Chauffeur & Refreshments)",
            baseFare = 280.0,
            perKmRate = 45.0,
            timeRatePerMin = 5.0,
            categoryMultiplier = 2.4,
            luxuryMultiplier = 2.0,
            engineCcFactor = 1.6,
            platformSafetyFee = 45.0,
            etaMins = 8,
            tag = "Luxury VIP"
        )
    )
}
