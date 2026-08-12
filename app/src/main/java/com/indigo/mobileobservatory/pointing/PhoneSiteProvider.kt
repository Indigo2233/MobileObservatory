package com.indigo.mobileobservatory.pointing

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.ObserverSite
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object PhoneSiteProvider {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    suspend fun currentSite(context: Context): ObserverSite {
        check(hasPermission(context)) { context.getString(R.string.location_permission_required) }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { manager.isProviderEnabled(it) }
        if (providers.isEmpty()) error(context.getString(R.string.location_provider_disabled))

        val last = providers.mapNotNull(manager::getLastKnownLocation)
            .maxWithOrNull(compareBy<Location> { it.time }.thenBy { -it.accuracy })
        if (last != null && System.currentTimeMillis() - last.time < 10 * 60 * 1000L) {
            return ObserverSite(last.latitude, last.longitude, last.altitude)
        }

        return withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine { continuation ->
                var completed = false
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (completed) return
                        completed = true
                        manager.removeUpdates(this)
                        continuation.resume(
                            ObserverSite(location.latitude, location.longitude, location.altitude)
                        )
                    }

                    @Deprecated("Deprecated in Android framework")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                providers.forEach { provider ->
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            }
        } ?: error(context.getString(R.string.phone_location_timeout))
    }
}
