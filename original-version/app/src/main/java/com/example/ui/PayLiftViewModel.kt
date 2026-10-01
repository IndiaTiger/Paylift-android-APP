package com.example.ui

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.PayLiftDatabase
import com.example.data.PayLiftRepository
import com.example.data.RideRecord
import com.example.data.UserProfile
import com.example.data.WalletTransaction
import com.example.domain.ActiveRideState
import com.example.domain.ChatMessage
import com.example.domain.DehradunLocations
import com.example.domain.DehradunPilots
import com.example.domain.FareBreakdown
import com.example.domain.FareEngine
import com.example.domain.FleetCatalog
import com.example.domain.LocationPoint
import com.example.domain.RideStage
import com.example.domain.VehicleOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

enum class AppNavTab {
    RIDE,
    WALLET,
    ACTIVITY,
    PROFILE
}

class PayLiftViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PayLiftRepository

    // Reactive DB States
    val userProfile: StateFlow<UserProfile>
    val transactions: StateFlow<List<WalletTransaction>>
    val pastRides: StateFlow<List<RideRecord>>

    // Navigation Tab
    private val _currentTab = MutableStateFlow(AppNavTab.RIDE)
    val currentTab: StateFlow<AppNavTab> = _currentTab.asStateFlow()

    // Dark Mode Override (true = dark, false = light, null = follow system)
    private val _isDarkMode = MutableStateFlow<Boolean?>(null)
    val isDarkMode: StateFlow<Boolean?> = _isDarkMode.asStateFlow()

    // Booking Route Selection
    val dehradunHubs = DehradunLocations.DEHRADUN_HUBS
    private val _pickup = MutableStateFlow(dehradunHubs[0]) // Clock Tower
    val pickup: StateFlow<LocationPoint> = _pickup.asStateFlow()

    private val _dropoff = MutableStateFlow(dehradunHubs[1]) // Rajpur Road
    val dropoff: StateFlow<LocationPoint> = _dropoff.asStateFlow()

    private val _selectedVehicle = MutableStateFlow(FleetCatalog.VEHICLES[3]) // PayLift Mini
    val selectedVehicle: StateFlow<VehicleOption> = _selectedVehicle.asStateFlow()

    // Dynamic Calculated Route Metrics
    private val _routeDistanceKm = MutableStateFlow(5.2)
    val routeDistanceKm: StateFlow<Double> = _routeDistanceKm.asStateFlow()

    private val _routeDurationMins = MutableStateFlow(16)
    val routeDurationMins: StateFlow<Int> = _routeDurationMins.asStateFlow()

    private val _currentFareBreakdown = MutableStateFlow(
        FareEngine.calculateFare(FleetCatalog.VEHICLES[3], 5.2, 16)
    )
    val currentFareBreakdown: StateFlow<FareBreakdown> = _currentFareBreakdown.asStateFlow()

    // Active Ride HUD & Telemetry State
    private val _activeRide = MutableStateFlow<ActiveRideState?>(null)
    val activeRide: StateFlow<ActiveRideState?> = _activeRide.asStateFlow()

    // Modals & Drawers
    private val _showFareBreakdownSheet = MutableStateFlow(false)
    val showFareBreakdownSheet: StateFlow<Boolean> = _showFareBreakdownSheet.asStateFlow()

    private val _showSosModal = MutableStateFlow(false)
    val showSosModal: StateFlow<Boolean> = _showSosModal.asStateFlow()

    private val _showChatModal = MutableStateFlow(false)
    val showChatModal: StateFlow<Boolean> = _showChatModal.asStateFlow()

    private val _showCallModal = MutableStateFlow(false)
    val showCallModal: StateFlow<Boolean> = _showCallModal.asStateFlow()

    private val _showCompletedReceiptModal = MutableStateFlow<RideRecord?>(null)
    val showCompletedReceiptModal: StateFlow<RideRecord?> = _showCompletedReceiptModal.asStateFlow()

    // UI Toast Notification Event
    private val _toastEvents = MutableSharedFlow<String>()
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    // Simulation Coroutine Job
    private var rideSimulationJob: Job? = null

    init {
        val db = PayLiftDatabase.getDatabase(application)
        repository = PayLiftRepository(db.walletDao(), db.rideDao(), db.userProfileDao())

        userProfile = repository.userProfile
            .map { it ?: UserProfile() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserProfile())

        transactions = repository.allTransactions
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        pastRides = repository.allRides
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        recalculateRouteMetrics()
    }

    fun selectTab(tab: AppNavTab) {
        _currentTab.value = tab
        triggerHaptic()
    }

    fun toggleDarkMode() {
        val current = _isDarkMode.value ?: false
        _isDarkMode.value = !current
        triggerHaptic()
    }

    fun setPickup(point: LocationPoint) {
        _pickup.value = point
        recalculateRouteMetrics()
        triggerHaptic()
    }

    fun setDropoff(point: LocationPoint) {
        _dropoff.value = point
        recalculateRouteMetrics()
        triggerHaptic()
    }

    fun swapPickupAndDrop() {
        val temp = _pickup.value
        _pickup.value = _dropoff.value
        _dropoff.value = temp
        recalculateRouteMetrics()
        triggerHaptic()
    }

    fun selectVehicle(vehicle: VehicleOption) {
        _selectedVehicle.value = vehicle
        recalculateRouteMetrics()
        triggerHaptic()
    }

    private fun recalculateRouteMetrics() {
        val p = _pickup.value
        val d = _dropoff.value
        val dist = DehradunLocations.calculateRoadDistance(p.latitude, p.longitude, d.latitude, d.longitude)
        val duration = DehradunLocations.estimateDurationMinutes(dist)
        _routeDistanceKm.value = dist
        _routeDurationMins.value = duration
        _currentFareBreakdown.value = FareEngine.calculateFare(_selectedVehicle.value, dist, duration)
    }

    fun openFareBreakdownSheet(open: Boolean) {
        _showFareBreakdownSheet.value = open
        triggerHaptic()
    }

    fun openSosModal(open: Boolean) {
        _showSosModal.value = open
        triggerHaptic()
    }

    fun openChatModal(open: Boolean) {
        _showChatModal.value = open
        triggerHaptic()
    }

    fun openCallModal(open: Boolean) {
        _showCallModal.value = open
        triggerHaptic()
    }

    fun closeReceiptModal() {
        _showCompletedReceiptModal.value = null
    }

    // --- Active Ride State Machine Simulation ---

    fun requestRide() {
        val vehicle = _selectedVehicle.value
        val p = _pickup.value
        val d = _dropoff.value
        val dist = _routeDistanceKm.value
        val dur = _routeDurationMins.value
        val fare = _currentFareBreakdown.value

        val profile = userProfile.value
        if (profile.walletBalance < fare.grossFinalFare) {
            viewModelScope.launch {
                _toastEvents.emit("Low balance! Top up ₹${"%.0f".format(fare.grossFinalFare - profile.walletBalance)} or use Quick Top-Up.")
                _currentTab.value = AppNavTab.WALLET
            }
            return
        }

        val polyline = DehradunLocations.generateRoutePolyline(
            p.latitude, p.longitude, d.latitude, d.longitude, steps = 24
        )
        val pilot = DehradunPilots.pickPilotForVehicle(vehicle)
        val rideId = "PL-DEH-${(1000..9999).random()}"
        val pinOtp = (1000..9999).random().toString()

        val initialRide = ActiveRideState(
            rideId = rideId,
            stage = RideStage.FINDING_PILOT,
            vehicleOption = vehicle,
            pickup = p,
            dropoff = d,
            distanceKm = dist,
            durationMinutes = dur,
            fareBreakdown = fare,
            securityPinOtp = pinOtp,
            pilot = pilot,
            progressFraction = 0f,
            speedKmph = 0,
            etaRemainingMinutes = dur,
            escrowLocked = true,
            routeWaypoints = polyline,
            chatMessages = listOf(
                ChatMessage(
                    sender = "System",
                    message = "PayLift Escrow of ₹${"%.1f".format(fare.grossFinalFare)} safely secured. Ride OTP is $pinOtp."
                )
            )
        )

        _activeRide.value = initialRide
        _showFareBreakdownSheet.value = false
        triggerHaptic()

        // Start lifecycle coroutine
        startRideLifecycleSimulation(rideId)
    }

    private fun startRideLifecycleSimulation(rideId: String) {
        rideSimulationJob?.cancel()
        rideSimulationJob = viewModelScope.launch {
            // Stage 1: Finding Pilot (2.5 seconds radar sweep)
            delay(2500)
            if (_activeRide.value?.rideId != rideId) return@launch

            _activeRide.value = _activeRide.value?.copy(
                stage = RideStage.PILOT_ASSIGNED,
                speedKmph = 28,
                etaRemainingMinutes = 3,
                chatMessages = _activeRide.value?.chatMessages.orEmpty() + ChatMessage(
                    sender = "Pilot",
                    message = "Namaste! I've accepted your ride. Arriving in 3 mins at ${_pickup.value.name}."
                )
            )
            _toastEvents.emit("Pilot ${_activeRide.value?.pilot?.name} assigned! UK07 Plate.")
            triggerHaptic()

            // Stage 2: Pilot Arrived (4 seconds later)
            delay(4000)
            if (_activeRide.value?.rideId != rideId) return@launch

            _activeRide.value = _activeRide.value?.copy(
                stage = RideStage.PILOT_ARRIVED,
                speedKmph = 0,
                etaRemainingMinutes = 0,
                chatMessages = _activeRide.value?.chatMessages.orEmpty() + ChatMessage(
                    sender = "Pilot",
                    message = "I have arrived at the pickup location. Please share your 4-digit PIN OTP."
                )
            )
            _toastEvents.emit("Pilot arrived at pickup! Share PIN ${_activeRide.value?.securityPinOtp}.")
            triggerHaptic()

            // Stage 3: In Transit (PIN verified)
            delay(3500)
            if (_activeRide.value?.rideId != rideId) return@launch

            _activeRide.value = _activeRide.value?.copy(
                stage = RideStage.IN_TRANSIT,
                chatMessages = _activeRide.value?.chatMessages.orEmpty() + ChatMessage(
                    sender = "System",
                    message = "PIN verified. Trip started towards ${_dropoff.value.name}."
                )
            )
            _toastEvents.emit("PIN Verified! Trip in transit.")
            triggerHaptic()

            // Progress loop along route polyline
            val totalSteps = 20
            for (step in 1..totalSteps) {
                delay(800)
                if (_activeRide.value?.rideId != rideId) return@launch

                val fraction = step.toFloat() / totalSteps.toFloat()
                val remainingMins = ((1f - fraction) * (_routeDurationMins.value)).toInt().coerceAtLeast(1)
                val currentSpeed = (32..46).random()

                _activeRide.value = _activeRide.value?.copy(
                    progressFraction = fraction,
                    speedKmph = currentSpeed,
                    etaRemainingMinutes = remainingMins
                )
            }

            // Stage 4: Completed
            delay(1000)
            if (_activeRide.value?.rideId != rideId) return@launch

            val finishedRide = _activeRide.value ?: return@launch
            _activeRide.value = finishedRide.copy(stage = RideStage.COMPLETED, progressFraction = 1f)

            // Debit from escrow wallet with HMAC validation
            val (success, _) = repository.debitRideFare(
                amount = finishedRide.fareBreakdown.grossFinalFare,
                pickupName = finishedRide.pickup.name,
                dropName = finishedRide.dropoff.name
            )

            val record = RideRecord(
                rideId = finishedRide.rideId,
                pickupName = finishedRide.pickup.name,
                dropName = finishedRide.dropoff.name,
                distanceKm = finishedRide.distanceKm,
                durationMin = finishedRide.durationMinutes,
                vehicleCategory = finishedRide.vehicleOption.categoryName,
                vehicleModel = "${finishedRide.vehicleOption.name} (${finishedRide.pilot.vehicleModel})",
                vehiclePlate = finishedRide.pilot.licensePlate,
                pilotName = finishedRide.pilot.name,
                pilotRating = finishedRide.pilot.rating,
                grossFare = finishedRide.fareBreakdown.grossFinalFare,
                pilotEarnings = finishedRide.fareBreakdown.pilotTakeHomeEarnings,
                platformCut = finishedRide.fareBreakdown.platformCutAmount,
                gstTax = finishedRide.fareBreakdown.gstTax,
                status = "COMPLETED"
            )

            repository.recordRide(record)
            _showCompletedReceiptModal.value = record
            _activeRide.value = null
            _toastEvents.emit("Trip completed! ₹${"%.1f".format(record.grossFare)} settled from Escrow.")
            triggerHaptic()
        }
    }

    fun cancelActiveRide() {
        val current = _activeRide.value ?: return
        rideSimulationJob?.cancel()

        viewModelScope.launch {
            val refundAmount = current.fareBreakdown.grossFinalFare
            repository.refundRide(
                amount = refundAmount,
                rideId = current.rideId,
                reason = "Rider Cancelled at ${current.stage.name}"
            )
            _activeRide.value = null
            _showChatModal.value = false
            _showCallModal.value = false
            _toastEvents.emit("Ride cancelled. Escrow refund of ₹${"%.1f".format(refundAmount)} returned to wallet.")
            triggerHaptic()
        }
    }

    fun sendChatMessage(text: String) {
        val current = _activeRide.value ?: return
        if (text.isBlank()) return
        val userMsg = ChatMessage(sender = "Rider", message = text.trim())
        val updatedMessages = current.chatMessages + userMsg
        _activeRide.value = current.copy(chatMessages = updatedMessages)

        // Pilot auto-acknowledgement after 1.2s
        viewModelScope.launch {
            delay(1200)
            val ackMsg = when {
                text.contains("gate", ignoreCase = true) -> "Got it! Right outside the main entrance."
                text.contains("AC", ignoreCase = true) -> "AC is already on high comfort. Let me know if you need temperature adjusted!"
                text.contains("down", ignoreCase = true) -> "Sure, take your time! Hazard lights on."
                else -> "Understood! See you shortly."
            }
            _activeRide.value = _activeRide.value?.let {
                it.copy(chatMessages = it.chatMessages + ChatMessage(sender = "Pilot", message = ackMsg))
            }
        }
    }

    fun submitPilotRating(rideId: String, stars: Int, feedbackTags: String) {
        viewModelScope.launch {
            repository.updateRideRating(rideId, stars, feedbackTags)
            _showCompletedReceiptModal.value = null
            _toastEvents.emit("Thank you! Pilot rated $stars ★")
            triggerHaptic()
        }
    }

    // --- Multi-Gateway Wallet Operations ---

    fun topUpWallet(amount: Double, gateway: String) {
        viewModelScope.launch {
            val (success, refOrMsg) = repository.topUpWallet(amount, gateway)
            if (success) {
                _toastEvents.emit("₹${"%.0f".format(amount)} added via $gateway! Ref: $refOrMsg (HMAC-SHA256 verified)")
                triggerHaptic()
            } else {
                _toastEvents.emit("Payment failed: $refOrMsg")
            }
        }
    }

    fun updateAutoTopupPreferences(enabled: Boolean, threshold: Double, amount: Double) {
        viewModelScope.launch {
            val current = userProfile.value
            repository.updateUserProfile(
                current.copy(
                    autoTopupEnabled = enabled,
                    autoTopupThreshold = threshold,
                    autoTopupAmount = amount
                )
            )
            _toastEvents.emit("Auto-topup preferences updated.")
            triggerHaptic()
        }
    }

    fun updateEmergencyContact(name: String, phone: String, relationship: String, autoDial: Boolean) {
        viewModelScope.launch {
            val current = userProfile.value
            repository.updateUserProfile(
                current.copy(
                    emergencyContactName = name,
                    emergencyContactPhone = phone,
                    emergencyRelationship = relationship,
                    autoDialSos = autoDial
                )
            )
            _toastEvents.emit("Emergency safety profile saved.")
            triggerHaptic()
        }
    }

    fun updatePaymentMethod(method: String) {
        viewModelScope.launch {
            val current = userProfile.value
            repository.updateUserProfile(current.copy(defaultPaymentMethod = method))
            _toastEvents.emit("Default payment method set to $method")
            triggerHaptic()
        }
    }

    private fun triggerHaptic() {
        try {
            val context = getApplication<Application>().applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(25)
            }
        } catch (_: Exception) {}
    }
}
