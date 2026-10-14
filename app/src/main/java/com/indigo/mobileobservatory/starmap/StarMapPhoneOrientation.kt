package com.indigo.mobileobservatory.starmap

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.indigo.mobileobservatory.astro.ObserverSite

/** Separate sensor subscription for sky browsing; never touches phone optical calibration. */
internal class StarMapPhoneOrientation(context: Context) : SensorEventListener {
    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val available: Boolean get() = sensor != null
    private var onDirection: ((PhoneBrowseDirection, Long) -> Unit)? = null
    private var declinationDeg = 0.0
    private var lastTimestampNs = 0L

    fun start(site: ObserverSite?, callback: (PhoneBrowseDirection, Long) -> Unit): Boolean {
        stop()
        val selected = sensor ?: return false
        declinationDeg = site?.let {
            GeomagneticField(it.latitudeDeg.toFloat(), it.longitudeDeg.toFloat(),
                it.elevationMeters.toFloat(), System.currentTimeMillis()).declination.toDouble()
        } ?: 0.0
        onDirection = callback
        // 20 Hz keeps gestures responsive without taking over the optical IMU source.
        if (manager.registerListener(this, selected, 50_000)) return true
        stop()
        return false
    }

    fun stop() {
        manager.unregisterListener(this)
        onDirection = null
        lastTimestampNs = 0L
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val callback = onDirection ?: return
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR || event.timestamp <= lastTimestampNs) return
        val ageNs = SystemClock.elapsedRealtimeNanos() - event.timestamp
        if (ageNs !in 0..500_000_000L) return
        lastTimestampNs = event.timestamp
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        PhoneBrowseDirection.fromRotationMatrix(matrix, declinationDeg)?.let {
            callback(it, System.currentTimeMillis() - ageNs / 1_000_000L)
        }
    }
}
