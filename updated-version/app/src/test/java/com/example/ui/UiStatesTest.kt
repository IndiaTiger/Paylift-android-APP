package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.core.AppError
import com.example.data.UserProfile
import com.example.domain.FleetCatalog
import com.example.domain.LocationPoint
import com.example.domain.PilotProfile
import com.example.services.OtpChallenge
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.RideBookingScreen
import com.example.ui.screens.WalletEscrowScreen
import com.example.ui.screens.modals.MaskedCallModal
import com.example.ui.screens.modals.SafetyCenterModal
import com.example.ui.theme.PayLiftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiStatesTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `login requests OTP then verifies`() {
        var requested = ""
        var verified = ""
        compose.setContent {
            PayLiftTheme(darkTheme = false) {
                LoginScreen(AuthUiState.SignedOut, OpState.Idle, { requested = it }, { verified = it }, {})
            }
        }
        compose.onNodeWithTag("auth_submit").assertIsNotEnabled()
        compose.onNodeWithTag("phone_input").performTextInput("9897012345")
        compose.onNodeWithTag("auth_submit").assertIsEnabled().performClick()
        assertEquals("9897012345", requested)
    }

    @Test
    fun `login shows server errors and dev OTP hint only when provided`() {
        compose.setContent {
            PayLiftTheme(darkTheme = false) {
                LoginScreen(
                    AuthUiState.OtpSent(OtpChallenge("+919897012345", "r", 300, "424242")),
                    OpState.Failed(AppError.Api(401, "OTP_INVALID", "Incorrect OTP")), {}, {}, {}
                )
            }
        }
        compose.onNodeWithTag("auth_error").assertIsDisplayed()
        compose.onNodeWithText("Incorrect OTP").assertIsDisplayed()
        compose.onNodeWithTag("dev_otp").assertIsDisplayed()
    }

    @Test
    fun `booking without a server quote shows status, retry and a disabled confirm`() {
        var retried = false
        compose.setContent {
            PayLiftTheme(darkTheme = false) {
                RideBookingScreen(
                    pickup = LocationPoint("A", "", 30.3255, 78.0436), dropoff = LocationPoint("B", "", 30.358, 78.072),
                    selectedVehicle = FleetCatalog.VEHICLES[3], fareBreakdown = null, distanceKm = 0.0, durationMins = 0,
                    walletBalance = 0.0, onSelectPickup = {}, onSelectDropoff = {}, onSwapLocations = {}, onSelectVehicle = {},
                    onOpenFareBreakdown = {}, onConfirmRide = { throw AssertionError("must not confirm without quote") },
                    quoteStatus = "Couldn't get a fare: You're offline.", onRetryQuote = { retried = true },
                )
            }
        }
        compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(androidx.compose.ui.test.hasTestTag("quote_status"))
        compose.onNodeWithTag("quote_status").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertTrue(retried)
        compose.onNodeWithTag("confirm_paylift_ride_button").assertIsNotEnabled()
    }

    @Test
    fun `wallet shows only the configured provider and disables top-up when payments are unavailable`() {
        compose.setContent {
            PayLiftTheme(darkTheme = false) {
                WalletEscrowScreen(UserProfile(), emptyList(), false, 0.0, { throw AssertionError("no top-up") }, { _, _, _ -> },
                    paymentProviderLabel = "Test Gateway", paymentsAvailable = false)
            }
        }
        compose.onNodeWithText("Not configured").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Razorpay").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("HMAC-256").fetchSemanticsNodes().size)
        compose.onNodeWithText("+₹100").performClick() // disabled: must not invoke onTopUp
    }

    @Test
    fun `SOS modal is truthful when no integration exists`() {
        compose.setContent {
            PayLiftTheme(darkTheme = false) {
                SafetyCenterModal(UserProfile(), LocationPoint("A", "", 30.3, 78.0), {}, {},
                    sosState = SosUiState.Unavailable("Emergency dispatch integration is not configured."))
            }
        }
        compose.onNodeWithTag("sos_unavailable").assertIsDisplayed()
        compose.onNodeWithText("CALL 112 NOW").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("SOS PROTOCOL BROADCASTED ✓").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("EMERGENCY ALERT SENT ✓").fetchSemanticsNodes().size)
    }

    @Test
    fun `masked call modal never shows a fake running call`() {
        compose.setContent {
            PayLiftTheme(darkTheme = false) {
                MaskedCallModal(PilotProfile("p", "Vikram", 4.9, 10, "Dzire", "White", "UK07", avatarInitials = "VR"), {},
                    callState = CallUiState.Unavailable("In-app calling isn't available yet."))
            }
        }
        compose.onNodeWithText("Call unavailable").assertIsDisplayed()
        compose.onNodeWithText("In-app calling isn't available yet.").assertIsDisplayed()
    }
}
