package com.example.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class LocationSource {
    /** From the device GPS fix. */
    GPS,
    /** Picked by the user on the map (reverse geocoded). */
    MAP_PIN,
    /** From a geocoding search result. */
    SEARCH,
    /** A saved address. */
    SAVED,
    /** Development geocoder data (never shown in release builds; flagged by the backend). */
    DEV_DATA,
    /** Nothing selected yet. */
    NONE,
}

data class LocationPoint(
    val name: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val category: String = "Place",
    val source: LocationSource = LocationSource.SEARCH,
) {
    val isSelected: Boolean get() = source != LocationSource.NONE

    companion object {
        /** Placeholder before the user picks a place. Coordinates only position the schematic map. */
        fun unselected(label: String) = LocationPoint(
            name = label, subtitle = "", latitude = MAP_REFERENCE_LAT, longitude = MAP_REFERENCE_LNG,
            category = "", source = LocationSource.NONE,
        )

        // Reference point of the schematic map canvas (Dehradun city centre).
        const val MAP_REFERENCE_LAT = 30.3255
        const val MAP_REFERENCE_LNG = 78.0436
    }
}

sealed interface TripValidation {
    data object Valid : TripValidation
    data class Invalid(val reason: String) : TripValidation
}

object LocationValidation {
    /** Must match the backend's minimum trip distance. */
    const val MIN_TRIP_METERS = 200.0

    fun isValidCoordinate(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)

    /** Client-side pre-check; the backend re-validates every trip. */
    fun validateTrip(pickup: LocationPoint, dropoff: LocationPoint): TripValidation = when {
        !pickup.isSelected -> TripValidation.Invalid("Choose a pickup location")
        !dropoff.isSelected -> TripValidation.Invalid("Choose a destination")
        !isValidCoordinate(pickup.latitude, pickup.longitude) -> TripValidation.Invalid("Pickup location is invalid")
        !isValidCoordinate(dropoff.latitude, dropoff.longitude) -> TripValidation.Invalid("Destination is invalid")
        haversineMeters(pickup.latitude, pickup.longitude, dropoff.latitude, dropoff.longitude) < MIN_TRIP_METERS ->
            TripValidation.Invalid("Pickup and destination must be at least 200 m apart")
        else -> TripValidation.Valid
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
