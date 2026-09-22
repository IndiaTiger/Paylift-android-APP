package com.example.domain

enum class RideStage {
    IDLE,
    FINDING_PILOT,
    PILOT_ASSIGNED,
    PILOT_ARRIVED,
    IN_TRANSIT,
    COMPLETED
}

data class PilotProfile(
    val pilotId: String,
    val name: String,
    val rating: Double,
    val totalTrips: Int,
    val vehicleModel: String,
    val vehicleColor: String,
    val licensePlate: String,
    val phoneProxy: String,
    val verifiedBadge: Boolean = true,
    val avatarInitials: String
)

data class ActiveRideState(
    val rideId: String,
    val stage: RideStage = RideStage.IDLE,
    val vehicleOption: VehicleOption,
    val pickup: LocationPoint,
    val dropoff: LocationPoint,
    val distanceKm: Double,
    val durationMinutes: Int,
    val fareBreakdown: FareBreakdown,
    val securityPinOtp: String, // 4-digit start code e.g. "5824"
    val pilot: PilotProfile,
    val progressFraction: Float = 0f, // 0.0 to 1.0 along route polyline
    val speedKmph: Int = 34,
    val etaRemainingMinutes: Int = 12,
    val escrowLocked: Boolean = true,
    val routeWaypoints: List<Pair<Double, Double>> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList()
)

data class ChatMessage(
    val sender: String, // "Rider", "Pilot", "System"
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

object DehradunPilots {
    val PILOT_POOL = listOf(
        PilotProfile(
            pilotId = "PL-PLT-8821",
            name = "Vikram Singh Rawat",
            rating = 4.92,
            totalTrips = 1840,
            vehicleModel = "Swift Dzire Prime",
            vehicleColor = "Arctic White",
            licensePlate = "UK07-BX-4921",
            phoneProxy = "+91 1800-729-5438 Ext 201",
            verifiedBadge = true,
            avatarInitials = "VR"
        ),
        PilotProfile(
            pilotId = "PL-PLT-7104",
            name = "Aman Negi",
            rating = 4.88,
            totalTrips = 960,
            vehicleModel = "Hero Splendor Pro",
            vehicleColor = "Jet Black",
            licensePlate = "UK07-CM-1088",
            phoneProxy = "+91 1800-729-5438 Ext 105",
            verifiedBadge = true,
            avatarInitials = "AN"
        ),
        PilotProfile(
            pilotId = "PL-PLT-9330",
            name = "Deepak Joshi",
            rating = 4.95,
            totalTrips = 2410,
            vehicleModel = "Toyota Innova Crysta",
            vehicleColor = "Silver Metallic",
            licensePlate = "UK07-TA-9930",
            phoneProxy = "+91 1800-729-5438 Ext 304",
            verifiedBadge = true,
            avatarInitials = "DJ"
        ),
        PilotProfile(
            pilotId = "PL-PLT-5512",
            name = "Rahul Chauhan",
            rating = 4.90,
            totalTrips = 1150,
            vehicleModel = "Ola S1 Pro (EV)",
            vehicleColor = "Electric Blue",
            licensePlate = "UK07-EV-5512",
            phoneProxy = "+91 1800-729-5438 Ext 112",
            verifiedBadge = true,
            avatarInitials = "RC"
        )
    )

    fun pickPilotForVehicle(vehicle: VehicleOption): PilotProfile {
        return when (vehicle.group) {
            VehicleTypeGroup.TWO_WHEELER -> {
                if (vehicle.id == "moto_electric") PILOT_POOL[3] else PILOT_POOL[1]
            }
            VehicleTypeGroup.FOUR_WHEELER -> {
                if (vehicle.id == "cab_suv" || vehicle.id == "cab_luxury") PILOT_POOL[2] else PILOT_POOL[0]
            }
        }
    }
}
