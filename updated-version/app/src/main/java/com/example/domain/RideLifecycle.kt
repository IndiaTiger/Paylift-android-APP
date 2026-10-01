package com.example.domain

/**
 * Server ride states (authoritative, see backend/src/rides.js). The client never advances a
 * ride on its own; it only renders what the server reports.
 */
enum class RideStatus {
    REQUESTED, SEARCHING, ASSIGNED, PILOT_ACCEPTED, PILOT_ARRIVING, ARRIVED, OTP_VERIFIED,
    IN_PROGRESS, COMPLETED, CANCELLED, FAILED, NO_PILOT_FOUND;

    val isTerminal: Boolean get() = this in TERMINAL

    /** States from which the rider may cancel (mirrors the server table). */
    val riderCanCancel: Boolean get() = this in CANCELLABLE

    /** Coarse stage used by the ride HUD. */
    val stage: RideStage get() = when (this) {
        REQUESTED, SEARCHING -> RideStage.FINDING_PILOT
        ASSIGNED, PILOT_ACCEPTED, PILOT_ARRIVING -> RideStage.PILOT_ASSIGNED
        ARRIVED, OTP_VERIFIED -> RideStage.PILOT_ARRIVED
        IN_PROGRESS -> RideStage.IN_TRANSIT
        COMPLETED, CANCELLED, FAILED, NO_PILOT_FOUND -> RideStage.COMPLETED
    }

    companion object {
        private val TERMINAL = setOf(COMPLETED, CANCELLED, FAILED, NO_PILOT_FOUND)
        private val CANCELLABLE = setOf(REQUESTED, SEARCHING, ASSIGNED, PILOT_ACCEPTED, PILOT_ARRIVING, ARRIVED, OTP_VERIFIED)

        /** Unknown values (newer server) are treated as FAILED rather than crashing. */
        fun parse(value: String?): RideStatus = entries.firstOrNull { it.name == value } ?: FAILED

        /**
         * Allowed transitions (target -> sources), identical to the server table. Used to reject
         * out-of-order or stale server snapshots (e.g. a delayed poll response arriving after a
         * newer one) so the UI never moves backwards.
         */
        val TRANSITIONS: Map<RideStatus, Set<RideStatus>> = mapOf(
            SEARCHING to setOf(REQUESTED, ASSIGNED, PILOT_ACCEPTED, PILOT_ARRIVING, ARRIVED),
            ASSIGNED to setOf(SEARCHING),
            PILOT_ACCEPTED to setOf(ASSIGNED),
            PILOT_ARRIVING to setOf(PILOT_ACCEPTED),
            ARRIVED to setOf(PILOT_ACCEPTED, PILOT_ARRIVING),
            OTP_VERIFIED to setOf(ARRIVED),
            IN_PROGRESS to setOf(OTP_VERIFIED),
            COMPLETED to setOf(IN_PROGRESS),
            CANCELLED to CANCELLABLE,
            NO_PILOT_FOUND to setOf(SEARCHING),
            FAILED to CANCELLABLE + IN_PROGRESS,
        )

        fun canTransition(from: RideStatus, to: RideStatus): Boolean = TRANSITIONS[to]?.contains(from) == true
    }
}

/** Coarse HUD stage (unchanged from the original UI). */
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
    /** Masked proxy number from a telephony provider. Empty when no provider is configured. */
    val phoneProxy: String = "",
    /** No verification provider exists yet, so the client never claims a pilot is verified. */
    val verifiedBadge: Boolean = false,
    val avatarInitials: String
) {
    companion object {
        /** Shown while the server is still searching (no pilot assigned yet). */
        val SEARCHING = PilotProfile(
            pilotId = "", name = "Searching…", rating = 0.0, totalTrips = 0, vehicleModel = "",
            vehicleColor = "", licensePlate = "", avatarInitials = "…"
        )
    }
}

data class ActiveRideState(
    val rideId: String,
    val status: RideStatus,
    /** Server version counter; snapshots with a lower version are ignored. */
    val version: Long,
    val stage: RideStage = status.stage,
    val vehicleOption: VehicleOption,
    val pickup: LocationPoint,
    val dropoff: LocationPoint,
    val distanceKm: Double,
    val durationMinutes: Int,
    val fareBreakdown: FareBreakdown,
    /** Start code shown to the rider; the pilot enters it on their device. */
    val securityPinOtp: String,
    val pilot: PilotProfile,
    val progressFraction: Float = 0f,
    val speedKmph: Int = 0,
    val etaRemainingMinutes: Int = 0,
    /** True while the fare is held by the server ledger. */
    val escrowLocked: Boolean = true,
    val routeWaypoints: List<Pair<Double, Double>> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList()
)

data class ChatMessage(
    val sender: String, // "Rider", "Pilot", "System"
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
