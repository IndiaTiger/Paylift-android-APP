package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.AppNavTab
import com.example.ui.PayLiftViewModel
import com.example.ui.components.PayLiftHeader
import com.example.ui.components.TranslucentNavBar
import com.example.ui.screens.ActiveRideHUD
import com.example.ui.screens.PastRidesScreen
import com.example.ui.screens.ProfileSettingsScreen
import com.example.ui.screens.RideBookingScreen
import com.example.ui.screens.WalletEscrowScreen
import com.example.ui.screens.modals.FareBreakdownSheet
import com.example.ui.screens.modals.InAppChatModal
import com.example.ui.screens.modals.LegalCenterModal
import com.example.ui.screens.modals.MaskedCallModal
import com.example.ui.screens.modals.RideReceiptModal
import com.example.ui.screens.modals.SafetyCenterModal
import com.example.ui.theme.PayLiftTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val viewModel: PayLiftViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val userDarkModePref by viewModel.isDarkMode.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val isDark = userDarkModePref ?: systemDark

            PayLiftTheme(darkTheme = isDark) {
                PayLiftApp(viewModel = viewModel, isDarkMode = isDark)
            }
        }
    }
}

@Composable
fun PayLiftApp(
    viewModel: PayLiftViewModel,
    isDarkMode: Boolean
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val pastRides by viewModel.pastRides.collectAsStateWithLifecycle()

    val pickup by viewModel.pickup.collectAsStateWithLifecycle()
    val dropoff by viewModel.dropoff.collectAsStateWithLifecycle()
    val selectedVehicle by viewModel.selectedVehicle.collectAsStateWithLifecycle()
    val fareBreakdown by viewModel.currentFareBreakdown.collectAsStateWithLifecycle()
    val distanceKm by viewModel.routeDistanceKm.collectAsStateWithLifecycle()
    val durationMins by viewModel.routeDurationMins.collectAsStateWithLifecycle()

    val activeRide by viewModel.activeRide.collectAsStateWithLifecycle()

    val showFareBreakdown by viewModel.showFareBreakdownSheet.collectAsStateWithLifecycle()
    val showSosModal by viewModel.showSosModal.collectAsStateWithLifecycle()
    val showChatModal by viewModel.showChatModal.collectAsStateWithLifecycle()
    val showCallModal by viewModel.showCallModal.collectAsStateWithLifecycle()
    val completedReceipt by viewModel.showCompletedReceiptModal.collectAsStateWithLifecycle()

    var activeLegalDoc by remember { mutableStateOf<String?>(null) }
    var selectedPastRideReceipt by remember { mutableStateOf<com.example.data.RideRecord?>(null) }

    // Collect Toast Events
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collectLatest { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            PayLiftHeader(
                walletBalance = userProfile.walletBalance,
                isDarkMode = isDarkMode,
                onToggleDarkMode = { viewModel.toggleDarkMode() },
                onOpenWallet = { viewModel.selectTab(AppNavTab.WALLET) },
                onOpenSos = { viewModel.openSosModal(true) }
            )
        },
        bottomBar = {
            TranslucentNavBar(
                currentTab = currentTab,
                onSelectTab = { viewModel.selectTab(it) },
                hasActiveRide = activeRide != null
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                AppNavTab.RIDE -> {
                    if (activeRide != null) {
                        ActiveRideHUD(
                            rideState = activeRide!!,
                            onOpenChat = { viewModel.openChatModal(true) },
                            onOpenCall = { viewModel.openCallModal(true) },
                            onCancelRide = { viewModel.cancelActiveRide() },
                            onOpenSos = { viewModel.openSosModal(true) }
                        )
                    } else {
                        RideBookingScreen(
                            pickup = pickup,
                            dropoff = dropoff,
                            selectedVehicle = selectedVehicle,
                            fareBreakdown = fareBreakdown,
                            distanceKm = distanceKm,
                            durationMins = durationMins,
                            walletBalance = userProfile.walletBalance,
                            onSelectPickup = { viewModel.setPickup(it) },
                            onSelectDropoff = { viewModel.setDropoff(it) },
                            onSwapLocations = { viewModel.swapPickupAndDrop() },
                            onSelectVehicle = { viewModel.selectVehicle(it) },
                            onOpenFareBreakdown = { viewModel.openFareBreakdownSheet(true) },
                            onConfirmRide = { viewModel.requestRide() }
                        )
                    }
                }

                AppNavTab.WALLET -> {
                    WalletEscrowScreen(
                        userProfile = userProfile,
                        transactions = transactions,
                        hasActiveRide = activeRide != null,
                        activeLockedEscrow = activeRide?.fareBreakdown?.grossFinalFare ?: 0.0,
                        onTopUp = { amount, gateway -> viewModel.topUpWallet(amount, gateway) },
                        onToggleAutoTopup = { enabled, thresh, amt ->
                            viewModel.updateAutoTopupPreferences(enabled, thresh, amt)
                        }
                    )
                }

                AppNavTab.ACTIVITY -> {
                    PastRidesScreen(
                        rides = pastRides,
                        onSelectRideReceipt = { selectedPastRideReceipt = it }
                    )
                }

                AppNavTab.PROFILE -> {
                    ProfileSettingsScreen(
                        userProfile = userProfile,
                        onUpdateEmergencyContact = { name, phone, rel ->
                            viewModel.updateEmergencyContact(name, phone, rel, userProfile.autoDialSos)
                        },
                        onToggleAutoDialSos = { autoDial ->
                            viewModel.updateEmergencyContact(
                                userProfile.emergencyContactName,
                                userProfile.emergencyContactPhone,
                                userProfile.emergencyRelationship,
                                autoDial
                            )
                        },
                        onOpenLegalModal = { activeLegalDoc = it }
                    )
                }
            }
        }
    }

    // Modal: Fare & Pilot Transparency Breakdown
    if (showFareBreakdown) {
        FareBreakdownSheet(
            vehicle = selectedVehicle,
            fareBreakdown = fareBreakdown,
            distanceKm = distanceKm,
            durationMins = durationMins,
            onDismiss = { viewModel.openFareBreakdownSheet(false) }
        )
    }

    // Modal: SOS Safety & Uttarakhand 112 Center
    if (showSosModal) {
        SafetyCenterModal(
            userProfile = userProfile,
            currentLocation = pickup,
            onToggleAutoDial = { autoDial ->
                viewModel.updateEmergencyContact(
                    userProfile.emergencyContactName,
                    userProfile.emergencyContactPhone,
                    userProfile.emergencyRelationship,
                    autoDial
                )
            },
            onDismiss = { viewModel.openSosModal(false) }
        )
    }

    // Modal: In-App Chat
    if (showChatModal && activeRide != null) {
        InAppChatModal(
            pilot = activeRide!!.pilot,
            messages = activeRide!!.chatMessages,
            onSendMessage = { viewModel.sendChatMessage(it) },
            onDismiss = { viewModel.openChatModal(false) }
        )
    }

    // Modal: Masked Phone Call
    if (showCallModal && activeRide != null) {
        MaskedCallModal(
            pilot = activeRide!!.pilot,
            onEndCall = { viewModel.openCallModal(false) }
        )
    }

    // Modal: Post-Ride Settled Receipt & 5-Star Pilot Rating
    if (completedReceipt != null) {
        RideReceiptModal(
            record = completedReceipt!!,
            onSubmitRating = { rating, tags ->
                viewModel.submitPilotRating(completedReceipt!!.rideId, rating, tags)
            },
            onDismiss = { viewModel.closeReceiptModal() }
        )
    }

    // Modal: Past Trip Receipt Review
    if (selectedPastRideReceipt != null) {
        RideReceiptModal(
            record = selectedPastRideReceipt!!,
            onSubmitRating = { rating, tags ->
                viewModel.submitPilotRating(selectedPastRideReceipt!!.rideId, rating, tags)
                selectedPastRideReceipt = null
            },
            onDismiss = { selectedPastRideReceipt = null }
        )
    }

    // Modal: Legal Center
    if (activeLegalDoc != null) {
        LegalCenterModal(
            documentTitle = activeLegalDoc!!,
            onDismiss = { activeLegalDoc = null }
        )
    }
}
