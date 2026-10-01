package com.example

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.AppError
import com.example.ui.AppNavTab
import com.example.ui.AuthUiState
import com.example.ui.OpState
import com.example.ui.PayLiftViewModel
import com.example.ui.QuoteUiState
import com.example.ui.components.PayLiftHeader
import com.example.ui.components.TranslucentNavBar
import com.example.ui.screens.ActiveRideHUD
import com.example.ui.screens.LoginScreen
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
import com.example.ui.theme.RoseEmergency
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

    override fun onStart() {
        super.onStart()
        // Returning from background / process restart: re-sync ride, wallet and pending payments.
        viewModel.onForeground()
    }
}

@Composable
fun PayLiftApp(
    viewModel: PayLiftViewModel,
    isDarkMode: Boolean
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect Toast Events
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collectLatest { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val auth by viewModel.auth.collectAsStateWithLifecycle()
    val authOp by viewModel.authOp.collectAsStateWithLifecycle()
    if (auth !is AuthUiState.SignedIn) {
        LoginScreen(
            authState = auth,
            opState = authOp,
            onRequestOtp = viewModel::requestOtp,
            onVerifyOtp = viewModel::verifyOtp,
            onChangeNumber = viewModel::changePhoneNumber,
            demoNotice = if (viewModel.isDemoMode) "Demo Mode: simulated services, no real money or SMS." else null,
        )
        return
    }

    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val pastRides by viewModel.pastRides.collectAsStateWithLifecycle()

    val pickup by viewModel.pickup.collectAsStateWithLifecycle()
    val dropoff by viewModel.dropoff.collectAsStateWithLifecycle()
    val selectedVehicle by viewModel.selectedVehicle.collectAsStateWithLifecycle()
    val vehicles by viewModel.vehicles.collectAsStateWithLifecycle()
    val fareBreakdown by viewModel.currentFareBreakdown.collectAsStateWithLifecycle()
    val quote by viewModel.quote.collectAsStateWithLifecycle()
    val distanceKm by viewModel.routeDistanceKm.collectAsStateWithLifecycle()
    val durationMins by viewModel.routeDurationMins.collectAsStateWithLifecycle()
    val routeWaypoints by viewModel.routeWaypoints.collectAsStateWithLifecycle()
    val places by viewModel.placeSuggestions.collectAsStateWithLifecycle()
    val requestOp by viewModel.requestOp.collectAsStateWithLifecycle()
    val topUpOp by viewModel.topUpOp.collectAsStateWithLifecycle()
    val cancelOp by viewModel.cancelOp.collectAsStateWithLifecycle()
    val serviceError by viewModel.serviceError.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()

    val activeRide by viewModel.activeRide.collectAsStateWithLifecycle()

    val showFareBreakdown by viewModel.showFareBreakdownSheet.collectAsStateWithLifecycle()
    val showSosModal by viewModel.showSosModal.collectAsStateWithLifecycle()
    val sosState by viewModel.sosState.collectAsStateWithLifecycle()
    val showChatModal by viewModel.showChatModal.collectAsStateWithLifecycle()
    val showCallModal by viewModel.showCallModal.collectAsStateWithLifecycle()
    val callState by viewModel.callState.collectAsStateWithLifecycle()
    val completedReceipt by viewModel.showCompletedReceiptModal.collectAsStateWithLifecycle()

    var activeLegalDoc by remember { mutableStateOf<String?>(null) }
    var selectedPastRideReceipt by remember { mutableStateOf<com.example.data.RideRecord?>(null) }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) viewModel.useCurrentLocationForPickup()
        else Toast.makeText(context, "Location permission denied. Pick your pickup on the map or search instead.", Toast.LENGTH_LONG).show()
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ServiceBanner(isOnline = isOnline, error = serviceError, onRetry = viewModel::retryServices)
            Box(modifier = Modifier.fillMaxSize()) {
                when (currentTab) {
                    AppNavTab.RIDE -> {
                        val ride = activeRide
                        if (ride != null) {
                            ActiveRideHUD(
                                rideState = ride,
                                onOpenChat = { viewModel.openChatModal(true) },
                                onOpenCall = { viewModel.openCallModal(true) },
                                onCancelRide = { viewModel.cancelActiveRide() },
                                onOpenSos = { viewModel.openSosModal(true) },
                                cancelInProgress = cancelOp == OpState.Loading
                            )
                        } else {
                            val readyQuote = (quote as? QuoteUiState.Ready)?.quote
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
                                onConfirmRide = { viewModel.requestRide() },
                                vehicles = vehicles,
                                places = places,
                                routeWaypoints = routeWaypoints,
                                routeDistanceMeters = readyQuote?.route?.distanceMeters ?: 0,
                                quoteStatus = when (val q = quote) {
                                    is QuoteUiState.NeedsInput -> q.message
                                    QuoteUiState.Loading -> "Getting fare from PayLift…"
                                    is QuoteUiState.Failed -> "Couldn't get a fare: ${q.error.message}"
                                    is QuoteUiState.Ready -> null
                                },
                                onRetryQuote = if (quote is QuoteUiState.Failed) viewModel::retryQuote else null,
                                requestInProgress = requestOp == OpState.Loading,
                                onUseCurrentLocation = {
                                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                },
                                onSearchPlaces = viewModel::searchPlaces,
                                onMapLongPress = { lat, lng -> viewModel.pinOnMap(lat, lng, asPickup = !pickup.isSelected) },
                            )
                        }
                    }

                    AppNavTab.WALLET -> {
                        WalletEscrowScreen(
                            userProfile = userProfile,
                            transactions = transactions,
                            hasActiveRide = activeRide != null,
                            activeLockedEscrow = userProfile.walletHeldPaise / 100.0,
                            onTopUp = { paise -> viewModel.topUpWallet(paise) },
                            onToggleAutoTopup = { enabled, thresh, amt ->
                                viewModel.updateAutoTopupPreferences(enabled, thresh, amt)
                            },
                            paymentProviderLabel = viewModel.paymentProviderLabel,
                            paymentsAvailable = viewModel.paymentsAvailable,
                            topUpInProgress = topUpOp == OpState.Loading
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
                            onOpenLegalModal = { activeLegalDoc = it },
                            onLogout = viewModel::logout,
                            onDeleteAccount = viewModel::deleteAccount
                        )
                    }
                }
            }
        }
    }

    // Modal: Fare & Pilot Transparency Breakdown (only with an authoritative quote)
    val sheetFare = fareBreakdown
    if (showFareBreakdown && sheetFare != null) {
        FareBreakdownSheet(
            vehicle = selectedVehicle,
            fareBreakdown = sheetFare,
            distanceKm = distanceKm,
            durationMins = durationMins,
            onDismiss = { viewModel.openFareBreakdownSheet(false) }
        )
    }

    // Modal: SOS Safety Center
    if (showSosModal) {
        SafetyCenterModal(
            userProfile = userProfile,
            currentLocation = activeRide?.pickup ?: pickup,
            onToggleAutoDial = { autoDial ->
                viewModel.updateEmergencyContact(
                    userProfile.emergencyContactName,
                    userProfile.emergencyContactPhone,
                    userProfile.emergencyRelationship,
                    autoDial
                )
            },
            onDismiss = { viewModel.openSosModal(false) },
            sosState = sosState,
            onActivateSos = viewModel::triggerSos
        )
    }

    // Modal: In-App Chat
    val chatRide = activeRide
    if (showChatModal && chatRide != null) {
        InAppChatModal(
            pilot = chatRide.pilot,
            messages = chatRide.chatMessages,
            onSendMessage = { viewModel.sendChatMessage(it) },
            onDismiss = { viewModel.openChatModal(false) }
        )
    }

    // Modal: Masked Phone Call
    if (showCallModal && chatRide != null) {
        MaskedCallModal(
            pilot = chatRide.pilot,
            onEndCall = { viewModel.openCallModal(false) },
            callState = callState
        )
    }

    // Modal: Post-Ride Receipt & Rating
    val receipt = completedReceipt
    if (receipt != null) {
        RideReceiptModal(
            record = receipt,
            onSubmitRating = { rating, tags -> viewModel.submitPilotRating(receipt.rideId, rating, tags) },
            onDismiss = { viewModel.closeReceiptModal() }
        )
    }

    // Modal: Past Trip Receipt Review
    val pastReceipt = selectedPastRideReceipt
    if (pastReceipt != null) {
        RideReceiptModal(
            record = pastReceipt,
            onSubmitRating = { rating, tags ->
                viewModel.submitPilotRating(pastReceipt.rideId, rating, tags)
                selectedPastRideReceipt = null
            },
            onDismiss = { selectedPastRideReceipt = null }
        )
    }

    // Modal: Legal Center
    val legal = activeLegalDoc
    if (legal != null) {
        LegalCenterModal(
            documentTitle = legal,
            onDismiss = { activeLegalDoc = null }
        )
    }
}

/** Offline / backend-unavailable banner with retry. Hidden when everything is fine. */
@Composable
private fun ServiceBanner(isOnline: Boolean, error: AppError?, onRetry: () -> Unit) {
    val message = when {
        !isOnline -> "You're offline. Showing last synced data."
        error is AppError.NotConfigured -> error.message
        error is AppError.Offline || error is AppError.Timeout -> "Can't reach PayLift servers."
        error is AppError.Api && error.httpStatus >= 500 -> "PayLift servers are having trouble."
        else -> null
    } ?: return
    Surface(color = RoseEmergency.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth().testTag("service_banner")) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (error !is AppError.NotConfigured) {
                Text(
                    "Retry",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = RoseEmergency,
                    modifier = Modifier.clickable(onClick = onRetry).padding(6.dp)
                )
            }
        }
    }
}
