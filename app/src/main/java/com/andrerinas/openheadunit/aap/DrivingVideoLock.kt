package com.andrerinas.openheadunit.aap

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.aap.protocol.messages.DrivingStatusEvent
import com.andrerinas.openheadunit.aap.protocol.proto.Sensors
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Tells Android Auto whether the car is moving, so video (a parked-only video app such as YouTube
 * or Fermata) stops while driving and comes back once the car has been stopped for a while.
 *
 * The head unit has no gear or parking-brake signal, so it goes by the unit's own GPS speed: moving
 * locks video at once, standing still for [DrivingVideoPolicy.STOPPED_HOLD_MS] unlocks it. Only
 * video is restricted ([NO_VIDEO]), never the keyboard or voice, so Maps works as it does today.
 * With no GPS fix (under a roof) the last state holds.
 */
object DrivingVideoLock {

    private val UNRESTRICTED = Sensors.SensorBatch.DrivingStatusData.Status.UNRESTRICTED
    private val NO_VIDEO = Sensors.SensorBatch.DrivingStatusData.Status.NO_VIDEO

    private var transport: AapTransport? = null
    private var locationManager: LocationManager? = null
    private val policy = DrivingVideoPolicy()

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!location.hasSpeed()) return
            policy.feed(location.speed, SystemClock.elapsedRealtime())?.let { send(it) }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /** The sensor channel opened: report parked (the car is still at the start) and start watching. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(context: Context, aap: AapTransport) {
        stop()
        transport = aap
        policy.reset()
        send(false)
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            locationManager = lm
            AppLog.i("DrivingVideoLock: watching GPS speed; video allowed until the car moves.")
        } catch (e: Exception) {
            AppLog.w("DrivingVideoLock: no GPS updates (${e.message}); video stays allowed.")
        }
    }

    @Synchronized
    fun stop() {
        try { locationManager?.removeUpdates(listener) } catch (e: Exception) { }
        locationManager = null
        transport = null
    }

    @Synchronized
    private fun send(moving: Boolean) {
        val aap = transport ?: return
        try {
            aap.send(DrivingStatusEvent(if (moving) NO_VIDEO else UNRESTRICTED))
            AppLog.i("DrivingVideoLock: " + if (moving) "moving, video off." else "parked, video allowed.")
        } catch (e: Exception) {
            AppLog.w("DrivingVideoLock: could not send the driving status: ${e.message}")
        }
    }
}

/**
 * Moving from [MOVING_MPS] at once; parked again only after [STOPPED_HOLD_MS] below [STOPPED_MPS].
 * Between the two (GPS drift at a standstill, a crawl) nothing changes. Returns the new state on a
 * change, else null.
 */
class DrivingVideoPolicy {
    companion object {
        const val MOVING_MPS = 2.5f       // 9 km/h
        const val STOPPED_MPS = 1.0f      // 3.6 km/h
        const val STOPPED_HOLD_MS = 60_000L
    }

    var moving = false
        private set
    private var stoppedSince = -1L

    fun reset() {
        moving = false
        stoppedSince = -1L
    }

    fun feed(speedMps: Float, nowMs: Long): Boolean? {
        if (speedMps >= MOVING_MPS) {
            stoppedSince = -1L
            if (!moving) { moving = true; return true }
            return null
        }
        if (speedMps < STOPPED_MPS) {
            if (stoppedSince < 0) stoppedSince = nowMs
            if (moving && nowMs - stoppedSince >= STOPPED_HOLD_MS) { moving = false; return false }
        } else {
            stoppedSince = -1L
        }
        return null
    }
}
