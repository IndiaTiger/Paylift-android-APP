package com.example.di

import android.content.Context
import com.example.api.ApiClient
import com.example.auth.KeystoreTokenStore
import com.example.auth.TokenStore
import com.example.config.AppConfig
import com.example.data.CacheDao
import com.example.data.PayLiftDatabase
import com.example.repository.LocationRepository
import com.example.repository.PaymentRepository
import com.example.repository.PilotRepository
import com.example.repository.ProfileRepository
import com.example.repository.RideRepository
import com.example.repository.SafetyRepository
import com.example.repository.SessionRepository
import com.example.services.AuthService
import com.example.services.LocationService
import com.example.services.MapsService
import com.example.services.NotificationService
import com.example.services.PaymentCheckout
import com.example.services.PaymentService
import com.example.services.PilotService
import com.example.services.RideService
import com.example.services.SafetyService
import com.example.services.UserService
import com.example.services.WalletService
import com.example.services.device.AndroidLocationService
import com.example.services.remote.PollingNotificationService
import com.example.services.remote.RemoteAuthService
import com.example.services.remote.RemoteMapsService
import com.example.services.remote.RemotePaymentService
import com.example.services.remote.RemotePilotService
import com.example.services.remote.RemoteRideService
import com.example.services.remote.RemoteSafetyService
import com.example.services.remote.RemoteUserService
import com.example.services.remote.RemoteWalletService
import com.example.usecase.CancelRideUseCase
import com.example.usecase.GetFareQuoteUseCase
import com.example.usecase.NearbyEtaUseCase
import com.example.usecase.RateRideUseCase
import com.example.usecase.RecoverStateUseCase
import com.example.usecase.RequestOtpUseCase
import com.example.usecase.RequestRideUseCase
import com.example.usecase.SearchPlacesUseCase
import com.example.usecase.TopUpWalletUseCase
import com.example.usecase.TriggerSosUseCase
import com.example.usecase.UpdateProfileUseCase
import com.example.usecase.VerifyOtpUseCase

/**
 * Service graph. Every external dependency is an interface so tests (and future providers) can
 * swap implementations without touching the UI. Build-variant specific pieces come from
 * [VariantModule] (src/debug vs src/release).
 */
class AppContainer(
    val config: AppConfig,
    val cache: CacheDao,
    val tokenStore: TokenStore,
    val authService: AuthService,
    val userService: UserService,
    val walletService: WalletService,
    val paymentService: PaymentService,
    val paymentCheckout: PaymentCheckout,
    val rideService: RideService,
    val mapsService: MapsService,
    val locationService: LocationService,
    val pilotService: PilotService,
    val notificationService: NotificationService,
    val safetyService: SafetyService,
) {
    val session = SessionRepository(authService, cache)
    val profile = ProfileRepository(userService, walletService, cache)
    val payments = PaymentRepository(paymentService, paymentCheckout, cache, profile)
    val rides = RideRepository(rideService, notificationService, cache)
    val locations = LocationRepository(mapsService, locationService, userService)
    val pilots = PilotRepository(pilotService)
    val safety = SafetyRepository(safetyService)

    val requestOtp = RequestOtpUseCase(session)
    val verifyOtp = VerifyOtpUseCase(session, profile)
    val getFareQuote = GetFareQuoteUseCase(rides)
    val requestRide = RequestRideUseCase(rides)
    val cancelRide = CancelRideUseCase(rides)
    val rateRide = RateRideUseCase(rides)
    val recoverState = RecoverStateUseCase(rides, payments, profile)
    val topUpWallet = TopUpWalletUseCase(payments)
    val updateProfile = UpdateProfileUseCase(profile)
    val searchPlaces = SearchPlacesUseCase(locations)
    val nearbyEta = NearbyEtaUseCase(pilots)
    val triggerSos = TriggerSosUseCase(safety)

    companion object {
        @Volatile private var instance: AppContainer? = null

        fun get(context: Context): AppContainer = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        /** Test hook: install a container built from fakes. */
        fun install(container: AppContainer) { instance = container }

        private fun create(context: Context): AppContainer {
            val config = AppConfig.fromBuildConfig()
            // The demo build type supplies an in-process demo engine; debug/release return null.
            VariantModule.createContainer(context, config)?.let { return it }
            val tokens = KeystoreTokenStore(context)
            val client = ApiClient(config.backendBaseUrl, tokens, VariantModule.interceptors())
            val rides = RemoteRideService(client)
            return AppContainer(
                config = config,
                cache = PayLiftDatabase.getDatabase(context).cacheDao(),
                tokenStore = tokens,
                authService = RemoteAuthService(client, tokens),
                userService = RemoteUserService(client),
                walletService = RemoteWalletService(client),
                paymentService = RemotePaymentService(client),
                paymentCheckout = VariantModule.paymentCheckout(client, config),
                rideService = rides,
                mapsService = RemoteMapsService(client),
                locationService = AndroidLocationService(context),
                pilotService = RemotePilotService(client),
                notificationService = PollingNotificationService(rides),
                safetyService = RemoteSafetyService(client),
            )
        }
    }
}
