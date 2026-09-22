package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.DehradunLocations
import com.example.domain.FareEngine
import com.example.domain.FleetCatalog
import com.example.security.CryptoSecurityEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context matches PayLift`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("PayLift", appName)
    }

    @Test
    fun `crypto security engine validates HMAC-SHA256 signatures`() {
        val timestamp = System.currentTimeMillis()
        val refId = CryptoSecurityEngine.generateReferenceId("RAZORPAY")
        val payload = "Razorpay|500.00|$refId|$timestamp"
        val signature = CryptoSecurityEngine.computeHmacSha256(payload)

        assertTrue(signature.isNotEmpty())
        assertEquals(64, signature.length)

        val isValid = CryptoSecurityEngine.verifySignature(
            gateway = "Razorpay",
            amount = 500.0,
            referenceId = refId,
            timestamp = timestamp,
            receivedSignature = signature
        )
        assertTrue(isValid)
    }

    @Test
    fun `fare engine computes authoritative formula and 85-15 split`() {
        val vehicle = FleetCatalog.VEHICLES[3] // PayLift Mini
        val distanceKm = 10.0
        val durationMin = 20

        val fare = FareEngine.calculateFare(vehicle, distanceKm, durationMin)

        assertTrue(fare.grossFinalFare > 0)
        assertTrue(fare.pilotTakeHomeEarnings > 0)
        assertTrue(fare.platformCutAmount > 0)

        // Pilot share should be exactly 85% of net fare
        val netFare = fare.grossFinalFare - fare.gstTax
        val expectedPilot = netFare * 0.85
        assertEquals(expectedPilot, fare.pilotTakeHomeEarnings, 0.05)
    }

    @Test
    fun `dehradun road distance applies winding factor`() {
        val clockTower = DehradunLocations.DEHRADUN_HUBS[0]
        val rajpurRoad = DehradunLocations.DEHRADUN_HUBS[1]

        val dist = DehradunLocations.calculateRoadDistance(
            clockTower.latitude, clockTower.longitude,
            rajpurRoad.latitude, rajpurRoad.longitude
        )

        assertTrue("Distance should be greater than 3km and less than 15km", dist in 3.0..15.0)
    }
}
