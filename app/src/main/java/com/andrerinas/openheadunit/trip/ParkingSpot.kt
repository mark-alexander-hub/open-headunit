package com.andrerinas.openheadunit.trip

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Where the vehicle was left: one location fix taken when a trip ends, so "where did I park" has
 * an answer later. This unit's GPS runs only for that fix - at most [MAX_WAIT_MS] - and is off
 * the rest of the time, so the cost is a minute of GPS per trip.
 */
object ParkingSpot {

    private const val PREFS = "trip_log"
    private const val KEY_LAT = "parking-lat"
    private const val KEY_LON = "parking-lon"
    private const val KEY_ACC = "parking-acc"
    private const val KEY_TIME = "parking-time"
    private const val MAX_WAIT_MS = 90_000L
    /** Good enough to stop early; a parked car is found by eye in the last 30 m. */
    private const val GOOD_ACCURACY_M = 30f
    /** A last known fix this fresh is still where the trip ended. */
    private const val LAST_KNOWN_MAX_AGE_MS = 5 * 60_000L

    data class Spot(val latitude: Double, val longitude: Double, val accuracyM: Float, val timeMs: Long)

    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private var best: Location? = null

    @SuppressLint("MissingPermission")
    fun capture(context: Context) {
        val app = context.applicationContext
        handler.post {
            if (listener != null) return@post // one capture at a time
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                AppLog.w("ParkingSpot: no location permission, not saving where the trip ended")
                return@post
            }
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@post
            best = freshLastKnown(lm)
            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (best == null || location.accuracy < best!!.accuracy) best = location
                    if (location.accuracy <= GOOD_ACCURACY_M) finish(app, lm)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            listener = l
            var started = false
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    if (lm.isProviderEnabled(provider)) {
                        lm.requestLocationUpdates(provider, 1000L, 0f, l, Looper.getMainLooper())
                        started = true
                    }
                } catch (e: Exception) {
                    AppLog.w("ParkingSpot: $provider unavailable: ${e.message}")
                }
            }
            if (!started) finish(app, lm) else handler.postDelayed({ finish(app, lm) }, MAX_WAIT_MS)
        }
    }

    private fun finish(context: Context, lm: LocationManager) {
        val l = listener ?: return
        listener = null
        handler.removeCallbacksAndMessages(null)
        try { lm.removeUpdates(l) } catch (_: Exception) {}
        val fix = best
        best = null
        if (fix == null) {
            AppLog.w("ParkingSpot: no fix when the trip ended, keeping the previous spot")
            return
        }
        prefs(context).edit()
            .putString(KEY_LAT, fix.latitude.toString())
            .putString(KEY_LON, fix.longitude.toString())
            .putFloat(KEY_ACC, fix.accuracy)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
        AppLog.i("ParkingSpot: saved, accuracy ${fix.accuracy.toInt()} m")
    }

    @SuppressLint("MissingPermission")
    private fun freshLastKnown(lm: LocationManager): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .filter { System.currentTimeMillis() - it.time <= LAST_KNOWN_MAX_AGE_MS }
            .minByOrNull { it.accuracy }

    fun load(context: Context): Spot? {
        val p = prefs(context)
        val lat = p.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = p.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
        return Spot(lat, lon, p.getFloat(KEY_ACC, 0f), p.getLong(KEY_TIME, 0L))
    }

    /** Any maps app on this unit, pinned on the spot. */
    fun mapIntent(spot: Spot): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${spot.latitude},${spot.longitude}(Parked)"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
