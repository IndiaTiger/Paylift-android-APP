package com.example.di

import android.content.Context
import com.example.api.ApiClient
import com.example.auth.InMemoryTokenStore
import com.example.config.AppConfig
import com.example.data.PayLiftDatabase
import com.example.dev.DemoEngine
import com.example.dev.TestGatewayCheckout
import com.example.services.PaymentCheckout
import com.example.services.remote.PollingNotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Interceptor

/**
 * DEMO wiring (build type `demo`, application id suffix `.demo`). Not part of release builds.
 *
 * The whole service graph is served by the in-process [DemoEngine], so the demo APK works on any
 * phone without a backend. Sign in with the documented demo credentials (demo/README.md).
 */
object VariantModule {
    fun interceptors(): List<Interceptor> = emptyList()

    fun paymentCheckout(client: ApiClient, config: AppConfig): PaymentCheckout = TestGatewayCheckout(client)

    fun createContainer(context: Context, config: AppConfig): AppContainer {
        val prefs = context.getSharedPreferences("paylift_demo_engine", Context.MODE_PRIVATE)
        val engine = DemoEngine(
            store = object : DemoEngine.Store {
                override fun load(): String? = prefs.getString("state", null)
                override fun save(json: String) { prefs.edit().putString("state", json).apply() }
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        return AppContainer(
            config = config.copy(backendBaseUrl = "", paymentProvider = "demo", authProvider = "demo"),
            cache = PayLiftDatabase.getDatabase(context).cacheDao(),
            tokenStore = InMemoryTokenStore(),
            authService = engine.auth,
            userService = engine.users,
            walletService = engine.walletService,
            paymentService = engine.paymentService,
            paymentCheckout = engine.checkout,
            rideService = engine.rideService,
            mapsService = engine.maps,
            locationService = engine.location,
            pilotService = engine.pilots,
            notificationService = PollingNotificationService(engine.rideService, intervalMs = 1_500),
            safetyService = engine.safety,
        )
    }
}
