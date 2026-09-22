package com.example.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LocationPoint(
    val name: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val category: String = "Hub" // "Transit", "Commercial", "Tourist", "Airport"
)

object DehradunLocations {

    val DEHRADUN_HUBS = listOf(
        LocationPoint(
            name = "Clock Tower (Ghanta Ghar)",
            subtitle = "City Center, Rajpur Road, Dehradun",
            latitude = 30.3255,
            longitude = 78.0436,
            category = "City Center"
        ),
        LocationPoint(
            name = "Rajpur Road (Pacific Mall)",
            subtitle = "Jakhan, Rajpur Road, Dehradun",
            latitude = 30.3580,
            longitude = 78.0720,
            category = "Commercial"
        ),
        LocationPoint(
            name = "ISBT Dehradun",
            subtitle = "Inter-State Bus Terminal, Haridwar Bypass",
            latitude = 30.2870,
            longitude = 78.0060,
            category = "Transit"
        ),
        LocationPoint(
            name = "Prem Nagar (Chakrata Road)",
            subtitle = "Near Graphic Era & IMA, Dehradun",
            latitude = 30.3340,
            longitude = 77.9620,
            category = "Institutional"
        ),
        LocationPoint(
            name = "Jolly Grant Airport (DED)",
            subtitle = "Dehradun Airport, Rishikesh Highway",
            latitude = 30.1900,
            longitude = 78.1800,
            category = "Airport"
        ),
        LocationPoint(
            name = "Sahastradhara Springs",
            subtitle = "Sulphur Springs & Ropeway, Sahastradhara Road",
            latitude = 30.3872,
            longitude = 78.1288,
            category = "Tourist"
        ),
        LocationPoint(
            name = "Rispana Pull (Bypass)",
            subtitle = "Haridwar-Dehradun Highway Intersection",
            latitude = 30.3015,
            longitude = 78.0645,
            category = "Transit"
        ),
        LocationPoint(
            name = "Ballupur Chowk",
            subtitle = "GMS Road / Kaulagarh Junction, Dehradun",
            latitude = 30.3370,
            longitude = 78.0160,
            category = "Junction"
        ),
        LocationPoint(
            name = "Forest Research Institute (FRI)",
            subtitle = "Chakrata Road, Kaulagarh, Dehradun",
            latitude = 30.3426,
            longitude = 77.9995,
            category = "Heritage"
        ),
        LocationPoint(
            name = "Paltan Bazaar",
            subtitle = "Near Dehradun Railway Station, City Heart",
            latitude = 30.3210,
            longitude = 78.0410,
            category = "Market"
        ),
        LocationPoint(
            name = "Mussoorie Diversion (Malsi)",
            subtitle = "Rajpur Foothills & Zoo, Mussoorie Highway",
            latitude = 30.3810,
            longitude = 78.0850,
            category = "Foothills"
        )
    )

    /**
     * Computes real road distance in kilometers using Haversine formula
     * multiplied by a realistic winding factor for Dehradun valley geography.
     */
    fun calculateRoadDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double,
        windingFactor: Double = 1.28
    ): Double {
        val r = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        val straightKm = r * c
        val actualRoadKm = straightKm * windingFactor
        return (Math.round(actualRoadKm * 10.0) / 10.0).coerceAtLeast(1.5)
    }

    /**
     * Realistic road transit duration computation based on Dehradun traffic conditions.
     */
    fun estimateDurationMinutes(distanceKm: Double): Int {
        // Average urban speed ~ 24 km/h with traffic lights and terrain
        val minutes = (distanceKm / 24.0 * 60.0).toInt() + 3
        return minutes.coerceAtLeast(5)
    }

    /**
     * Generates intermediate polyline waypoints between pickup and dropoff
     * to render smooth road geometry and drive vehicle navigation.
     */
    fun generateRoutePolyline(
        startLat: Double, startLon: Double,
        endLat: Double, endLon: Double,
        steps: Int = 12
    ): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        for (i in 0..steps) {
            val fraction = i.toDouble() / steps.toDouble()
            // Add subtle curving road perturbations
            val curveOffset = sin(fraction * Math.PI) * 0.004 * if ((i % 2) == 0) 1.0 else -0.8
            val lat = startLat + (endLat - startLat) * fraction + curveOffset
            val lon = startLon + (endLon - startLon) * fraction + curveOffset * 0.7
            points.add(Pair(lat, lon))
        }
        return points
    }
}
