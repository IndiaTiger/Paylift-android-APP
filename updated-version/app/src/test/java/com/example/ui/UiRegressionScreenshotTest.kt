package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.data.RideRecord
import com.example.data.UserProfile
import com.example.data.WalletTransaction
import com.example.domain.FareCalculatorTest
import com.example.domain.FleetCatalog
import com.example.domain.LocationPoint
import com.example.ui.components.PayLiftHeader
import com.example.ui.components.TranslucentNavBar
import com.example.ui.screens.PastRidesScreen
import com.example.ui.screens.ProfileSettingsScreen
import com.example.ui.screens.RideBookingScreen
import com.example.ui.screens.WalletEscrowScreen
import com.example.ui.screens.modals.FareBreakdownSheet
import com.example.ui.theme.PayLiftTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI regression screenshots. The reference images in src/test/screenshots were recorded from the
 * ORIGINAL prototype UI before the production refactor (a pristine copy is kept in
 * docs/ui-baseline-original). `./gradlew verifyRoborazziDebug` compares the current UI, fed with
 * the same inputs, against them. Intentional differences are listed in docs/UI_CHANGES.md.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class UiRegressionScreenshotTest {

    @get:Rule val compose = createComposeRule()

    // Same inputs as the baseline recording.
    private val hubs = listOf(
        LocationPoint("Clock Tower (Ghanta Ghar)", "City Center, Rajpur Road, Dehradun", 30.3255, 78.0436, "City Center"),
        LocationPoint("Rajpur Road (Pacific Mall)", "Jakhan, Rajpur Road, Dehradun", 30.3580, 78.0720, "Commercial"),
    )
    private val vehicle = FleetCatalog.VEHICLES[3]
    private val fare = FareCalculatorTest.quoteFor(vehicle, 5200, 16).toBreakdown()
    private val profile = UserProfile(
        name = "Mohit Tyagi", email = "mtyagi45678909@gmail.com", phone = "+91 98970 12345",
        emergencyContactName = "Pooja Sharma", emergencyContactPhone = "+91 98112 34567",
        emergencyRelationship = "Sister / Family", walletBalance = 650.0, walletAvailablePaise = 65_000,
        lifetimeSavings = 340.0, autoDialSos = true, walletLoaded = true,
    )
    private val txs = listOf(
        WalletTransaction(
            transactionId = "TXN-A1", timestamp = 1_700_000_000_000L, type = "credit",
            category = "TOPUP", amount = 500.0, balanceAfter = 500.0, gateway = "Razorpay",
            status = "SUCCESS", referenceId = "pay_ref_1", description = "Wallet top-up"
        )
    )
    private val ride = RideRecord(
        rideId = "PL-DEH-4912", timestamp = 1_700_000_000_000L, pickupName = hubs[0].name,
        dropName = hubs[1].name, distanceKm = 5.2, durationMin = 18,
        vehicleCategory = "Cars & Cabs", vehicleModel = "Swift Dzire", vehiclePlate = "UK07-AX-8219",
        pilotName = "Aman Negi", pilotRating = 4.9, grossFare = 125.0, pilotEarnings = 106.25,
        platformCut = 18.75, gstTax = 5.95, status = "COMPLETED", userRating = 5,
        feedbackTags = "Polite Pilot", chargedAmount = 125.0
    )

    private fun snap(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { PayLiftTheme(darkTheme = false) { content() } }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun header() = snap("header") {
        PayLiftHeader(walletBalance = 650.0, isDarkMode = false, onToggleDarkMode = {}, onOpenWallet = {}, onOpenSos = {})
    }

    @Test fun navBar() = snap("nav_bar") {
        TranslucentNavBar(currentTab = AppNavTab.RIDE, onSelectTab = {}, hasActiveRide = false)
    }

    @Test fun booking() = snap("booking") {
        RideBookingScreen(
            pickup = hubs[0], dropoff = hubs[1], selectedVehicle = vehicle, fareBreakdown = fare,
            distanceKm = 5.2, durationMins = 16, walletBalance = 650.0, onSelectPickup = {},
            onSelectDropoff = {}, onSwapLocations = {}, onSelectVehicle = {},
            onOpenFareBreakdown = {}, onConfirmRide = {},
            places = hubs, routeWaypoints = listOf(30.3255 to 78.0436, 30.3580 to 78.0720), routeDistanceMeters = 5200,
        )
    }

    @Test fun wallet() = snap("wallet") {
        WalletEscrowScreen(
            userProfile = profile, transactions = txs, hasActiveRide = false,
            activeLockedEscrow = 0.0, onTopUp = { }, onToggleAutoTopup = { _, _, _ -> }
        )
    }

    @Test fun history() = snap("history") {
        PastRidesScreen(rides = listOf(ride), onSelectRideReceipt = {})
    }

    @Test fun profile() = snap("profile") {
        ProfileSettingsScreen(
            userProfile = profile, onUpdateEmergencyContact = { _, _, _ -> },
            onToggleAutoDialSos = {}, onOpenLegalModal = {}
        )
    }

    @Test fun fareSheet() = snap("fare_sheet") {
        FareBreakdownSheet(vehicle = vehicle, fareBreakdown = fare, distanceKm = 5.2, durationMins = 16, onDismiss = {})
    }
}
