package com.example.config

import com.example.BuildConfig

/**
 * Client configuration, compiled from the whitelisted keys in `.env` / environment
 * (see app/build.gradle.kts and .env.example). Contains PUBLIC values only.
 *
 * An empty value means "not configured": the corresponding feature reports itself as
 * unavailable instead of falling back to fake behaviour.
 */
data class AppConfig(
    val backendBaseUrl: String,
    val paymentProvider: String,
    val paymentPublicKey: String,
    val mapsApiKey: String,
    val placesApiKey: String,
    val routingApiKey: String,
    val authProvider: String,
    /** True only in debug builds. Release builds compile this to `false`. */
    val devToolsEnabled: Boolean,
    /** True only in the `demo` build type (in-process DemoEngine, demo credentials). */
    val demoMode: Boolean = false,
) {
    val isBackendConfigured: Boolean get() = backendBaseUrl.isNotBlank()

    companion object {
        fun fromBuildConfig(): AppConfig {
            // Debug builds may talk to the local test backend; release builds only ever use
            // BACKEND_BASE_URL (DEV_BACKEND_BASE_URL is compiled to "" for release).
            val backend = BuildConfig.BACKEND_BASE_URL.ifBlank {
                if (BuildConfig.DEV_TOOLS_ENABLED) BuildConfig.DEV_BACKEND_BASE_URL else ""
            }
            return AppConfig(
                backendBaseUrl = backend.let { if (it.isBlank() || it.endsWith("/")) it else "$it/" },
                paymentProvider = BuildConfig.PAYMENT_PROVIDER,
                paymentPublicKey = BuildConfig.PAYMENT_PUBLIC_KEY,
                mapsApiKey = BuildConfig.MAPS_API_KEY,
                placesApiKey = BuildConfig.PLACES_API_KEY,
                routingApiKey = BuildConfig.ROUTING_API_KEY,
                authProvider = BuildConfig.AUTH_PROVIDER,
                devToolsEnabled = BuildConfig.DEV_TOOLS_ENABLED,
                demoMode = BuildConfig.DEMO_MODE,
            )
        }
    }
}
