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
    data class SavedSite(val site: ObserverSite, val origin: String, val recordedUtcMs: Long)
    private fun prefs(context: Context) = context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
    fun savedSite(context: Context): SavedSite? {
        val prefs = prefs(context)
        val latitude = prefs.getString("observer_site_latitude", null)?.toDoubleOrNull()
        val longitude = prefs.getString("observer_site_longitude", null)?.toDoubleOrNull()
        if (latitude != null && longitude != null && valid(latitude, longitude)) return SavedSite(
            ObserverSite(latitude, longitude, prefs.getString("observer_site_elevation", null)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0),
            prefs.getString("observer_site_origin", "saved") ?: "saved", prefs.getLong("observer_site_recorded_utc", 0))
        if (prefs.contains("polar_latitude_deg") && prefs.contains("polar_longitude_deg")) {
            val lat = prefs.getFloat("polar_latitude_deg", Float.NaN).toDouble()
            val lon = prefs.getFloat("polar_longitude_deg", Float.NaN).toDouble()
            if (valid(lat, lon)) return SavedSite(ObserverSite(lat, lon), "saved", 0)
        }
        return null
    }
    private fun valid(latitude: Double, longitude: Double) = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
    fun saveSite(context: Context, site: ObserverSite, origin: String = "manual", recordedUtcMs: Long = System.currentTimeMillis()) {
        require(valid(site.latitudeDeg, site.longitudeDeg) && site.elevationMeters.isFinite())
        prefs(context).edit().putString("observer_site_latitude", site.latitudeDeg.toString())
            .putString("observer_site_longitude", site.longitudeDeg.toString())
            .putString("observer_site_elevation", site.elevationMeters.toString()).putString("observer_site_origin", origin)
            .putLong("observer_site_recorded_utc", recordedUtcMs).apply()
    }
    fun canResolveSite(context: Context) = savedSite(context) != null || hasPermission(context)
    internal fun acceptPhoneSite(context: Context, site: ObserverSite, forcePhoneLocation: Boolean, recordedUtcMs: Long): ObserverSite {
        val chosen = savedSite(context)
        // An automatic request started earlier cannot replace a newer explicit manual choice.
        if (!forcePhoneLocation && chosen?.origin == "manual") return chosen.site
        saveSite(context, site, "gps", recordedUtcMs)
        return site
    }
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    suspend fun currentSite(context: Context, forcePhoneLocation: Boolean = false): ObserverSite {
        val saved = savedSite(context)
        if (!forcePhoneLocation && saved?.origin == "manual") return saved.site
        if (!hasPermission(context) && !forcePhoneLocation && saved != null) return saved.site
        check(hasPermission(context)) { context.getString(R.string.location_permission_required) }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { manager.isProviderEnabled(it) }
        if (providers.isEmpty()) return if (!forcePhoneLocation && saved != null) saved.site
            else error(context.getString(R.string.location_provider_disabled))

        val last = providers.mapNotNull(manager::getLastKnownLocation)
            .maxWithOrNull(compareBy<Location> { it.time }.thenBy { -it.accuracy })
        if (last != null && System.currentTimeMillis() - last.time in 0..(10 * 60 * 1000L)) {
            return acceptPhoneSite(context, ObserverSite(last.latitude, last.longitude, last.altitude), forcePhoneLocation, last.time)
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
        }?.let { acceptPhoneSite(context, it, forcePhoneLocation, System.currentTimeMillis()) }
            ?: if (!forcePhoneLocation && saved != null) saved.site else error(context.getString(R.string.phone_location_timeout))
    }
}
