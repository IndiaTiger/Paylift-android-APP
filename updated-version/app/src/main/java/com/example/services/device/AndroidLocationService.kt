package com.example.services.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.example.core.AppError
import com.example.core.Outcome
import com.example.services.LocationService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** GPS via the platform LocationManager (no Play Services dependency). */
class AndroidLocationService(context: Context) : LocationService {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked via hasPermission()
    override suspend fun currentLocation(): Outcome<Pair<Double, Double>> {
        if (!hasPermission()) return Outcome.Failure(AppError.Api(403, "LOCATION_PERMISSION", "Location permission is required"))
        val providers = manager.getProviders(true).filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
        if (providers.isEmpty()) return Outcome.Failure(AppError.Api(503, "LOCATION_DISABLED", "Turn on device location"))
        val fresh = withTimeoutOrNull(10_000) { freshFix(providers.first()) }
        val fix = fresh ?: providers.mapNotNull { manager.getLastKnownLocation(it) }
            .filter { System.currentTimeMillis() - it.time < 5 * 60_000 }
            .maxByOrNull { it.time }
        return fix?.let { Outcome.Success(it.latitude to it.longitude) }
            ?: Outcome.Failure(AppError.Api(504, "LOCATION_UNAVAILABLE", "Couldn't get your location. Try again or pick on the map."))
    }

    @SuppressLint("MissingPermission")
    private suspend fun freshFix(provider: String): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, appContext.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
        }
    }
}
