package com.example.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.AppError
import com.example.core.Outcome
import com.example.data.RideRecord
import com.example.data.UserProfile
import com.example.data.WalletTransaction
import com.example.di.AppContainer
import com.example.domain.ActiveRideState
import com.example.domain.ChatMessage
import com.example.domain.FareBreakdown
import com.example.domain.FleetCatalog
import com.example.domain.LocationPoint
import com.example.domain.LocationSource
import com.example.domain.PilotProfile
import com.example.domain.RideStatus
import com.example.domain.VehicleOption
import com.example.repository.TopUpResult
import com.example.repository.toCache
import com.example.repository.toUi
import com.example.services.OtpChallenge
import com.example.services.ProfilePatch
import com.example.services.Ride
import com.example.services.RideQuote
import com.example.services.SosResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppNavTab {
    RIDE,
    WALLET,
    ACTIVITY,
    PROFILE
}

/** State of a user-triggered operation. Every important action exposes one of these. */
sealed interface OpState {
    data object Idle : OpState
    data object Loading : OpState
    data class Failed(val error: AppError) : OpState
}

sealed interface AuthUiState {
    data object SignedOut : AuthUiState
    data class OtpSent(val challenge: OtpChallenge) : AuthUiState
    data object SignedIn : AuthUiState
}

sealed interface QuoteUiState {
    /** Pickup/destination not chosen yet (or trip invalid). */
    data class NeedsInput(val message: String) : QuoteUiState
    data object Loading : QuoteUiState
    data class Ready(val quote: RideQuote) : QuoteUiState
    data class Failed(val error: AppError) : QuoteUiState
}

sealed interface SosUiState {
    data object Idle : SosUiState
    data object Sending : SosUiState
    data object Dispatched : SosUiState
    data class Unavailable(val message: String) : SosUiState
}

sealed interface CallUiState {
    data object Connecting : CallUiState
    data class Unavailable(val message: String) : CallUiState
}

class PayLiftViewModel @JvmOverloads constructor(
    application: Application,
    private val container: AppContainer = AppContainer.get(application),
) : AndroidViewModel(application) {

    // ------------------------------------------------------------------ Server-backed data
    val userProfile: StateFlow<UserProfile> =
        container.profile.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserProfile())
    val transactions: StateFlow<List<WalletTransaction>> =
        container.profile.transactions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pastRides: StateFlow<List<RideRecord>> =
        container.rides.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ------------------------------------------------------------------ Auth / connectivity
    private val _auth = MutableStateFlow<AuthUiState>(if (container.session.isSignedIn) AuthUiState.SignedIn else AuthUiState.SignedOut)
    val auth: StateFlow<AuthUiState> = _auth.asStateFlow()
    private val _authOp = MutableStateFlow<OpState>(OpState.Idle)
    val authOp: StateFlow<OpState> = _authOp.asStateFlow()

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    /** Non-null when the backend is unreachable/unconfigured, shown as a banner with Retry. */
    private val _serviceError = MutableStateFlow<AppError?>(null)
    val serviceError: StateFlow<AppError?> = _serviceError.asStateFlow()

    // ------------------------------------------------------------------ Navigation / theme
    private val _currentTab = MutableStateFlow(AppNavTab.RIDE)
    val currentTab: StateFlow<AppNavTab> = _currentTab.asStateFlow()

    private val _isDarkMode = MutableStateFlow<Boolean?>(null)
    val isDarkMode: StateFlow<Boolean?> = _isDarkMode.asStateFlow()

    // ------------------------------------------------------------------ Booking
    private val _pickup = MutableStateFlow(LocationPoint.unselected("Set pickup location"))
    val pickup: StateFlow<LocationPoint> = _pickup.asStateFlow()

    private val _dropoff = MutableStateFlow(LocationPoint.unselected("Set destination"))
    val dropoff: StateFlow<LocationPoint> = _dropoff.asStateFlow()

    private val _selectedVehicle = MutableStateFlow(FleetCatalog.VEHICLES[3])
    val selectedVehicle: StateFlow<VehicleOption> = _selectedVehicle.asStateFlow()

    /** Catalog entries with live ETA from nearby available pilots. */
    private val _vehicles = MutableStateFlow(FleetCatalog.VEHICLES)
    val vehicles: StateFlow<List<VehicleOption>> = _vehicles.asStateFlow()

    private val _quote = MutableStateFlow<QuoteUiState>(QuoteUiState.NeedsInput("Choose pickup and destination"))
    val quote: StateFlow<QuoteUiState> = _quote.asStateFlow()

    private val _currentFareBreakdown = MutableStateFlow<FareBreakdown?>(null)
    val currentFareBreakdown: StateFlow<FareBreakdown?> = _currentFareBreakdown.asStateFlow()

    private val _routeDistanceKm = MutableStateFlow(0.0)
    val routeDistanceKm: StateFlow<Double> = _routeDistanceKm.asStateFlow()

    private val _routeDurationMins = MutableStateFlow(0)
    val routeDurationMins: StateFlow<Int> = _routeDurationMins.asStateFlow()

    private val _routeWaypoints = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val routeWaypoints: StateFlow<List<Pair<Double, Double>>> = _routeWaypoints.asStateFlow()

    /** Places offered in the pickup/destination selectors (GPS, saved addresses, search results). */
    private val _placeSuggestions = MutableStateFlow<List<LocationPoint>>(emptyList())
    val placeSuggestions: StateFlow<List<LocationPoint>> = _placeSuggestions.asStateFlow()

    private val _placesOp = MutableStateFlow<OpState>(OpState.Idle)
    val placesOp: StateFlow<OpState> = _placesOp.asStateFlow()

    private val _requestOp = MutableStateFlow<OpState>(OpState.Idle)
    val requestOp: StateFlow<OpState> = _requestOp.asStateFlow()

    private val _topUpOp = MutableStateFlow<OpState>(OpState.Idle)
    val topUpOp: StateFlow<OpState> = _topUpOp.asStateFlow()

    /** Demo build only: shown as a small notice on the sign-in screen. */
    val isDemoMode: Boolean get() = container.config.demoMode

    val paymentProviderLabel: String get() = container.payments.providerLabel
    val paymentsAvailable: Boolean get() = container.payments.isAvailable

    // ------------------------------------------------------------------ Active ride
    private val _activeRide = MutableStateFlow<ActiveRideState?>(null)
    val activeRide: StateFlow<ActiveRideState?> = _activeRide.asStateFlow()

    private val _cancelOp = MutableStateFlow<OpState>(OpState.Idle)
    val cancelOp: StateFlow<OpState> = _cancelOp.asStateFlow()

    // ------------------------------------------------------------------ Modals
    private val _showFareBreakdownSheet = MutableStateFlow(false)
    val showFareBreakdownSheet: StateFlow<Boolean> = _showFareBreakdownSheet.asStateFlow()

    private val _showSosModal = MutableStateFlow(false)
    val showSosModal: StateFlow<Boolean> = _showSosModal.asStateFlow()

    private val _sosState = MutableStateFlow<SosUiState>(SosUiState.Idle)
    val sosState: StateFlow<SosUiState> = _sosState.asStateFlow()

    private val _showChatModal = MutableStateFlow(false)
    val showChatModal: StateFlow<Boolean> = _showChatModal.asStateFlow()

    private val _showCallModal = MutableStateFlow(false)
    val showCallModal: StateFlow<Boolean> = _showCallModal.asStateFlow()

    private val _callState = MutableStateFlow<CallUiState>(CallUiState.Connecting)
    val callState: StateFlow<CallUiState> = _callState.asStateFlow()

    private val _showCompletedReceiptModal = MutableStateFlow<RideRecord?>(null)
    val showCompletedReceiptModal: StateFlow<RideRecord?> = _showCompletedReceiptModal.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private var rideObserverJob: Job? = null
    private var messagesJob: Job? = null
    private var quoteJob: Job? = null
    private var searchJob: Job? = null
    private var pendingTopUpKey: Pair<Long, String>? = null

    init {
        viewModelScope.launch {
            container.session.sessionExpired.collect {
                container.session.onSessionExpired()
                stopRideObservation()
                _activeRide.value = null
                _auth.value = AuthUiState.SignedOut
                toast(AppError.Unauthorized.message)
            }
        }
        registerConnectivity()
        if (container.session.isSignedIn) refreshAll()
    }

    // ================================================================== Auth
    fun requestOtp(phone: String) = launchOp(_authOp) {
        when (val r = container.requestOtp(phone)) {
            is Outcome.Success -> { _auth.value = AuthUiState.OtpSent(r.value); OpState.Idle }
            is Outcome.Failure -> OpState.Failed(r.error)
        }
    }

    fun verifyOtp(otp: String) {
        val challenge = (_auth.value as? AuthUiState.OtpSent)?.challenge ?: return
        launchOp(_authOp) {
            when (val r = container.verifyOtp(challenge, otp)) {
                is Outcome.Success -> { _auth.value = AuthUiState.SignedIn; refreshAll(); OpState.Idle }
                is Outcome.Failure -> OpState.Failed(r.error)
            }
        }
    }

    fun changePhoneNumber() {
        _auth.value = AuthUiState.SignedOut
        _authOp.value = OpState.Idle
    }

    fun logout() {
        viewModelScope.launch {
            stopRideObservation()
            val r = container.session.logout()
            _activeRide.value = null
            _auth.value = AuthUiState.SignedOut
            _currentTab.value = AppNavTab.RIDE
            if (r is Outcome.Failure) toast("Signed out on this device. Server sign-out failed: ${r.error.message}")
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            when (val r = container.session.deleteAccount()) {
                is Outcome.Success -> {
                    stopRideObservation()
                    _activeRide.value = null
                    _auth.value = AuthUiState.SignedOut
                    toast("Your account was deleted.")
                }
                is Outcome.Failure -> toast("Couldn't delete account: ${r.error.message}")
            }
        }
    }

    // ================================================================== Refresh / recovery
    /** Loads profile, wallet, history and recovers any active ride / unconfirmed payment. */
    fun refreshAll() {
        viewModelScope.launch {
            when (val r = container.recoverState()) {
                is Outcome.Success -> {
                    _serviceError.value = null
                    r.value?.let { applyRideSnapshot(it); startRideObservation(it.rideId) }
                        ?: run { if (_activeRide.value != null) { _activeRide.value = null; stopRideObservation() } }
                }
                is Outcome.Failure -> _serviceError.value = r.error
            }
            loadDefaultPlaces()
            refreshNearbyEta()
        }
    }

    /** Called when the app returns to the foreground. */
    fun onForeground() {
        if (_auth.value is AuthUiState.SignedIn) refreshAll()
    }

    private fun registerConnectivity() {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        _isOnline.value = cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } ?: false
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val wasOffline = !_isOnline.value
                    _isOnline.value = true
                    if (wasOffline) onForeground() // reconnect: re-sync with the server
                }
                override fun onLost(network: Network) { _isOnline.value = false }
            })
        } catch (_: SecurityException) {
            // ACCESS_NETWORK_STATE missing: fall back to per-request error mapping.
        }
    }

    // ================================================================== Navigation / theme
    fun selectTab(tab: AppNavTab) {
        _currentTab.value = tab
        triggerHaptic()
        when (tab) {
            AppNavTab.WALLET -> viewModelScope.launch { reportIfFailed(container.profile.refreshWallet()) }
            AppNavTab.ACTIVITY -> viewModelScope.launch { reportIfFailed(container.rides.refreshHistory()) }
            else -> Unit
        }
    }

    fun toggleDarkMode() {
        _isDarkMode.value = !(_isDarkMode.value ?: false)
        triggerHaptic()
    }

    // ================================================================== Places
    private suspend fun loadDefaultPlaces() {
        val saved = (container.locations.savedAddresses() as? Outcome.Success)?.value?.map { it.place }.orEmpty()
        val initial = (container.searchPlaces("") as? Outcome.Success)?.value.orEmpty()
        _placeSuggestions.value = (saved + initial).distinctBy { it.name to it.latitude }
    }

    fun searchPlaces(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300) // debounce
            _placesOp.value = OpState.Loading
            when (val r = container.searchPlaces(query)) {
                is Outcome.Success -> { _placeSuggestions.value = r.value; _placesOp.value = OpState.Idle }
                is Outcome.Failure -> _placesOp.value = OpState.Failed(r.error)
            }
        }
    }

    /** GPS pickup. The Activity requests the runtime permission first. */
    fun useCurrentLocationForPickup() {
        viewModelScope.launch {
            if (!container.locations.hasLocationPermission()) {
                toast("Location permission is needed to use your current location.")
                return@launch
            }
            _placesOp.value = OpState.Loading
            when (val r = container.locations.currentPlace()) {
                is Outcome.Success -> { _placesOp.value = OpState.Idle; setPickup(r.value) }
                is Outcome.Failure -> { _placesOp.value = OpState.Failed(r.error); toast(r.error.message) }
            }
        }
    }

    /** Manual map selection: long-press on the map drops a pin that is reverse geocoded. */
    fun pinOnMap(lat: Double, lng: Double, asPickup: Boolean) {
        viewModelScope.launch {
            when (val r = container.locations.pinAt(lat, lng)) {
                is Outcome.Success -> if (asPickup) setPickup(r.value) else setDropoff(r.value)
                is Outcome.Failure -> toast("Couldn't resolve that point: ${r.error.message}")
            }
        }
    }

    fun saveAddress(label: String, place: LocationPoint) {
        viewModelScope.launch {
            when (val r = container.locations.saveAddress(label, place)) {
                is Outcome.Success -> { toast("Saved \"$label\""); loadDefaultPlaces() }
                is Outcome.Failure -> toast("Couldn't save address: ${r.error.message}")
            }
        }
    }

    // ================================================================== Booking
    fun setPickup(point: LocationPoint) {
        _pickup.value = point
        onTripChanged()
        refreshNearbyEta()
        triggerHaptic()
    }

    fun setDropoff(point: LocationPoint) {
        _dropoff.value = point
        onTripChanged()
        triggerHaptic()
    }

    fun swapPickupAndDrop() {
        val temp = _pickup.value
        _pickup.value = _dropoff.value
        _dropoff.value = temp
        onTripChanged()
        refreshNearbyEta()
        triggerHaptic()
    }

    fun selectVehicle(vehicle: VehicleOption) {
        _selectedVehicle.value = vehicle
        onTripChanged()
        triggerHaptic()
    }

    fun retryQuote() = onTripChanged()

    /** Fetches the authoritative server quote for the current trip. */
    private fun onTripChanged() {
        quoteJob?.cancel()
        _currentFareBreakdown.value = null
        val p = _pickup.value
        val d = _dropoff.value
        if (!p.isSelected || !d.isSelected) {
            _quote.value = QuoteUiState.NeedsInput("Choose pickup and destination")
            _routeWaypoints.value = emptyList()
            _routeDistanceKm.value = 0.0
            _routeDurationMins.value = 0
            return
        }
        _quote.value = QuoteUiState.Loading
        quoteJob = viewModelScope.launch {
            when (val r = container.getFareQuote(_selectedVehicle.value.id, p, d)) {
                is Outcome.Success -> applyQuote(r.value)
                is Outcome.Failure -> _quote.value = if (r.error is AppError.Api && (r.error as AppError.Api).code == "INVALID_TRIP") {
                    QuoteUiState.NeedsInput(r.error.message)
                } else {
                    QuoteUiState.Failed(r.error)
                }
            }
        }
    }

    private fun applyQuote(q: RideQuote) {
        _quote.value = QuoteUiState.Ready(q)
        _currentFareBreakdown.value = q.fare.toBreakdown()
        _routeDistanceKm.value = Math.round(q.route.distanceMeters / 100.0) / 10.0
        _routeDurationMins.value = q.route.durationMinutes.toInt()
        _routeWaypoints.value = q.route.polyline
    }

    private fun refreshNearbyEta() {
        viewModelScope.launch {
            val eta = (container.nearbyEta(_pickup.value) as? Outcome.Success)?.value.orEmpty()
            _vehicles.value = FleetCatalog.VEHICLES.map { it.copy(etaMins = eta[it.id]) }
            _selectedVehicle.value = _vehicles.value.first { it.id == _selectedVehicle.value.id }
        }
    }

    fun openFareBreakdownSheet(open: Boolean) {
        _showFareBreakdownSheet.value = open && _currentFareBreakdown.value != null
        triggerHaptic()
    }

    /** Double-tap safe: ignored while a request is in flight; the repository also serializes. */
    fun requestRide() {
        if (_requestOp.value == OpState.Loading || _activeRide.value != null) return
        val quote = (_quote.value as? QuoteUiState.Ready)?.quote
        launchOp(_requestOp) {
            when (val r = container.requestRide(quote, userProfile.value.walletAvailablePaise)) {
                is Outcome.Success -> {
                    _showFareBreakdownSheet.value = false
                    applyRideSnapshot(r.value)
                    startRideObservation(r.value.rideId)
                    triggerHaptic()
                    container.profile.refreshWallet()
                    OpState.Idle
                }
                is Outcome.Failure -> {
                    val e = r.error
                    when {
                        e is AppError.Api && e.code == "INSUFFICIENT_FUNDS" -> {
                            val shortBy = ((quote?.fare?.totalPaise ?: 0) - userProfile.value.walletAvailablePaise).coerceAtLeast(0)
                            toast("Low balance! Top up ₹${"%.0f".format(Math.ceil(shortBy / 100.0))} to book this ride.")
                            _currentTab.value = AppNavTab.WALLET
                        }
                        e is AppError.Api && (e.code == "QUOTE_EXPIRED" || e.code == "FARE_CHANGED") -> {
                            toast("The fare was updated. Please review and confirm again.")
                            onTripChanged()
                        }
                        e is AppError.Api && e.code == "ACTIVE_RIDE_EXISTS" -> refreshAll()
                        else -> toast(e.message)
                    }
                    OpState.Failed(e)
                }
            }
        }
    }

    // ================================================================== Active ride
    private fun startRideObservation(rideId: String) {
        if (rideObserverJob?.isActive == true && _activeRide.value?.rideId == rideId) return
        rideObserverJob?.cancel()
        rideObserverJob = viewModelScope.launch {
            container.rides.observe(rideId).collect { r ->
                when (r) {
                    is Outcome.Success -> { _serviceError.value = null; container.rides.track(r.value); applyRideSnapshot(r.value) }
                    is Outcome.Failure -> if (r.error is AppError.Offline || r.error is AppError.Timeout) _serviceError.value = r.error
                }
            }
        }
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            while (true) {
                (container.rides.messages(rideId) as? Outcome.Success)?.value?.let { msgs ->
                    _activeRide.value = _activeRide.value?.takeIf { it.rideId == rideId }?.copy(
                        chatMessages = msgs.map { ChatMessage(it.senderRole.replaceFirstChar(Char::uppercase), it.body, it.createdAt) }
                    )
                }
                delay(if (_showChatModal.value) 2000 else 6000)
            }
        }
    }

    private fun stopRideObservation() {
        rideObserverJob?.cancel(); rideObserverJob = null
        messagesJob?.cancel(); messagesJob = null
    }

    /** Applies a server snapshot. Older snapshots (lower version) are ignored. */
    private fun applyRideSnapshot(ride: Ride) {
        val current = _activeRide.value
        if (current != null && current.rideId == ride.rideId && ride.version < current.version) return
        if (ride.status.isTerminal) {
            onRideEnded(ride)
            return
        }
        val vehicle = FleetCatalog.VEHICLES.firstOrNull { it.id == ride.vehicleId } ?: _selectedVehicle.value
        _activeRide.value = ActiveRideState(
            rideId = ride.rideId,
            status = ride.status,
            version = ride.version,
            vehicleOption = vehicle,
            pickup = ride.pickup,
            dropoff = ride.dropoff,
            distanceKm = Math.round(ride.distanceMeters / 100.0) / 10.0,
            durationMinutes = ride.durationMinutes.toInt(),
            fareBreakdown = ride.fare.toBreakdown(),
            securityPinOtp = ride.startOtp ?: "----",
            pilot = ride.pilot?.let {
                PilotProfile(it.pilotId, it.name, it.rating, it.totalTrips, it.vehicleModel, it.vehicleColor, it.licensePlate, avatarInitials = it.avatarInitials)
            } ?: PilotProfile.SEARCHING,
            progressFraction = ride.progressFraction.toFloat(),
            speedKmph = 0,
            etaRemainingMinutes = if (ride.status == RideStatus.IN_PROGRESS) {
                Math.round((1 - ride.progressFraction) * ride.durationMinutes).toInt().coerceAtLeast(1)
            } else ride.durationMinutes.toInt(),
            escrowLocked = true,
            routeWaypoints = ride.polyline,
            chatMessages = current?.takeIf { it.rideId == ride.rideId }?.chatMessages.orEmpty(),
        )
        if (current?.status != ride.status && current?.rideId == ride.rideId) {
            statusMessage(ride)?.let { toast(it) }
            triggerHaptic()
        }
    }

    private fun statusMessage(ride: Ride): String? = when (ride.status) {
        RideStatus.ASSIGNED -> "Pilot ${ride.pilot?.name.orEmpty()} assigned."
        RideStatus.PILOT_ACCEPTED -> "Pilot accepted your ride."
        RideStatus.ARRIVED -> "Pilot arrived at pickup! Share PIN ${ride.startOtp.orEmpty()}."
        RideStatus.IN_PROGRESS -> "PIN verified. Trip started."
        RideStatus.SEARCHING -> "Looking for another pilot…"
        else -> null
    }

    private fun onRideEnded(ride: Ride) {
        stopRideObservation()
        _activeRide.value = null
        _showChatModal.value = false
        _showCallModal.value = false
        viewModelScope.launch {
            container.rides.track(ride)
            container.profile.refreshWallet()
            container.rides.refreshHistory()
        }
        val rupees = { p: Long -> "%.2f".format(p / 100.0) }
        when (ride.status) {
            RideStatus.COMPLETED -> {
                _showCompletedReceiptModal.value = ride.toCache().toUi()
                toast("Trip completed! ₹${rupees(ride.chargedPaise)} charged from your wallet.")
            }
            RideStatus.CANCELLED -> toast(
                if (ride.chargedPaise > 0) "Ride cancelled. Cancellation fee ₹${rupees(ride.chargedPaise)} charged; the rest of the hold was released."
                else "Ride cancelled. Your held fare was released to your wallet."
            )
            RideStatus.NO_PILOT_FOUND -> toast("No pilot was available. Your held fare was released.")
            RideStatus.FAILED -> toast("The ride could not be completed. Your held fare was released.")
            else -> Unit
        }
    }

    fun cancelActiveRide() {
        val current = _activeRide.value ?: return
        if (_cancelOp.value == OpState.Loading) return
        launchOp(_cancelOp) {
            when (val r = container.cancelRide(current.rideId, current.status, "Rider cancelled")) {
                is Outcome.Success -> { applyRideSnapshot(r.value); OpState.Idle }
                is Outcome.Failure -> {
                    // e.g. the trip started or completed concurrently: show the server's truth.
                    toast("Couldn't cancel: ${r.error.message}")
                    (container.rides.recover() as? Outcome.Success)?.value?.let { applyRideSnapshot(it) }
                    OpState.Failed(r.error)
                }
            }
        }
    }

    fun sendChatMessage(text: String) {
        val current = _activeRide.value ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            when (val r = container.rides.sendMessage(current.rideId, text.trim())) {
                is Outcome.Success -> _activeRide.value = _activeRide.value?.let {
                    it.copy(chatMessages = it.chatMessages + ChatMessage("Rider", r.value.body, r.value.createdAt))
                }
                is Outcome.Failure -> toast("Message not sent: ${r.error.message}")
            }
        }
    }

    fun submitPilotRating(rideId: String, stars: Int, feedbackTags: String) {
        viewModelScope.launch {
            when (val r = container.rateRide(rideId, stars, feedbackTags)) {
                is Outcome.Success -> {
                    _showCompletedReceiptModal.value = null
                    toast("Thank you! Pilot rated $stars ★")
                    triggerHaptic()
                }
                is Outcome.Failure -> toast("Rating not saved: ${r.error.message}")
            }
        }
    }

    // ================================================================== Wallet
    /**
     * Starts a top-up. The same idempotency key is reused if the user retries the same amount
     * after a failure/timeout, so a retried payment can never be charged twice.
     */
    fun topUpWallet(amountPaise: Long) {
        if (_topUpOp.value == OpState.Loading) return
        val key = pendingTopUpKey?.takeIf { it.first == amountPaise }?.second ?: container.payments.newIdempotencyKey()
        pendingTopUpKey = amountPaise to key
        launchOp(_topUpOp) {
            when (val r = container.topUpWallet(amountPaise, key)) {
                is Outcome.Success -> {
                    when (val res = r.value) {
                        is TopUpResult.Credited -> { pendingTopUpKey = null; toast("₹${"%.2f".format(amountPaise / 100.0)} added to your wallet."); triggerHaptic() }
                        is TopUpResult.Failed -> { pendingTopUpKey = null; toast("Payment failed: ${res.reason}") }
                        TopUpResult.Cancelled -> { pendingTopUpKey = null; toast("Payment cancelled. No money was added.") }
                        TopUpResult.PendingConfirmation -> toast("Payment is being confirmed. Your balance will update once the payment provider confirms it.")
                    }
                    OpState.Idle
                }
                is Outcome.Failure -> {
                    if (!r.error.isRetryable) pendingTopUpKey = null
                    toast("Payment failed: ${r.error.message}")
                    OpState.Failed(r.error)
                }
            }
        }
    }

    /** Auto top-up requires a recurring payment mandate; it is not offered until the gateway supports it. */
    fun updateAutoTopupPreferences(@Suppress("UNUSED_PARAMETER") enabled: Boolean, threshold: Double, amount: Double) {
        toast("Auto top-up isn't available yet.")
    }

    // ================================================================== Profile
    fun updateEmergencyContact(name: String, phone: String, relationship: String, autoDial: Boolean) {
        viewModelScope.launch {
            when (val r = container.updateProfile(ProfilePatch(emergencyContactName = name, emergencyContactPhone = phone, emergencyRelationship = relationship, autoDialSos = autoDial))) {
                is Outcome.Success -> { toast("Emergency safety profile saved."); triggerHaptic() }
                is Outcome.Failure -> toast("Not saved: ${r.error.message}")
            }
        }
    }

    fun updateName(name: String, email: String) {
        viewModelScope.launch {
            when (val r = container.updateProfile(ProfilePatch(name = name, email = email))) {
                is Outcome.Success -> toast("Profile saved.")
                is Outcome.Failure -> toast("Not saved: ${r.error.message}")
            }
        }
    }

    // ================================================================== Modals / safety / call
    fun openSosModal(open: Boolean) {
        _showSosModal.value = open
        if (!open) _sosState.value = SosUiState.Idle
        triggerHaptic()
    }

    fun triggerSos() {
        if (_sosState.value == SosUiState.Sending) return
        _sosState.value = SosUiState.Sending
        val ride = _activeRide.value
        val p = ride?.pickup ?: _pickup.value
        viewModelScope.launch {
            _sosState.value = when (val r = container.triggerSos(p.latitude.takeIf { p.isSelected }, p.longitude.takeIf { p.isSelected }, ride?.rideId)) {
                is Outcome.Success -> when (val res = r.value) {
                    SosResult.Dispatched -> SosUiState.Dispatched
                    is SosResult.Unavailable -> SosUiState.Unavailable(res.message)
                }
                is Outcome.Failure -> SosUiState.Unavailable("Emergency alert could not be sent (${r.error.message}). Call 112 directly.")
            }
        }
    }

    fun openChatModal(open: Boolean) {
        _showChatModal.value = open
        triggerHaptic()
    }

    fun openCallModal(open: Boolean) {
        _showCallModal.value = open
        triggerHaptic()
        if (!open) return
        val ride = _activeRide.value ?: return
        _callState.value = CallUiState.Connecting
        viewModelScope.launch {
            _callState.value = when (val r = container.rides.requestMaskedCall(ride.rideId)) {
                is Outcome.Success -> CallUiState.Connecting
                is Outcome.Failure -> CallUiState.Unavailable(
                    if (r.error is AppError.NotConfigured) "In-app calling isn't available yet. Use chat to reach your pilot." else r.error.message
                )
            }
        }
    }

    fun closeReceiptModal() {
        _showCompletedReceiptModal.value = null
    }

    fun retryServices() = refreshAll()

    // ================================================================== Helpers
    private fun launchOp(state: MutableStateFlow<OpState>, block: suspend () -> OpState) {
        if (state.value == OpState.Loading) return
        state.value = OpState.Loading
        viewModelScope.launch {
            state.value = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                OpState.Failed(AppError.Unexpected(e.message ?: "Unexpected error"))
            }
        }
    }

    private fun reportIfFailed(r: Outcome<*>) {
        if (r is Outcome.Failure) {
            _serviceError.value = r.error
            if (r.error !is AppError.NotConfigured) toast(r.error.message)
        } else {
            _serviceError.value = null
        }
    }

    private fun toast(msg: String) {
        _toastEvents.tryEmit(msg)
    }

    private fun triggerHaptic() {
        try {
            val context = getApplication<Application>().applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(25)
            }
        } catch (_: Exception) {
            // Haptics are cosmetic; devices without a vibrator are fine.
        }
    }
}
