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
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Where the vehicle was left: one location fix taken when a trip ends, so "where did I park" has
 * an answer later.
 *
 * A fix asked for only at the end often never comes: the GPS was off for the whole trip and
 * airplane mode left it no network assistance, so it starts cold, often parked under a roof. So
 * during the trip the GPS keeps a slow fix every [TRACK_INTERVAL_MS] and the end starts from the
 * newest of those, which is saved straight away; the end-of-trip listen only improves on it.
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
    /** Slow enough to cost little over a ride, fast enough that the last fix is near the stop. */
    private const val TRACK_INTERVAL_MS = 15_000L

    data class Spot(val latitude: Double, val longitude: Double, val accuracyM: Float, val timeMs: Long)

    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private var best: Location? = null
    private var tracker: LocationListener? = null
    private var lastTracked: Location? = null
    /** [best] is still the trip's last fix, taken rolling, rather than one from the stop itself. */
    private var bestIsTripFix = false
    private var timeout: Runnable? = null
    private var captureLock: PowerManager.WakeLock? = null

    /** The trip started: keep a slow GPS fix going so its end has a recent one to start from. */
    @SuppressLint("MissingPermission")
    fun startTracking(context: Context) {
        val app = context.applicationContext
        handler.post {
            if (tracker != null) return@post
            if (!hasPermission(app)) return@post
            val everySeconds = try { com.andrerinas.openheadunit.App.provide(app).settings.parkingTrackSeconds } catch (e: Exception) { 15 }
            if (everySeconds <= 0) {
                AppLog.i("ParkingSpot: trip tracking is off, the spot is left to the end of the trip")
                return@post
            }
            val intervalMs = everySeconds.coerceIn(5, 600) * 1000L
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@post
            val t = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (lastTracked == null) AppLog.i("ParkingSpot: first trip fix, accuracy ${location.accuracy.toInt()} m")
                    lastTracked = location
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, t, Looper.getMainLooper())
                    tracker = t
                }
            } catch (e: Exception) {
                AppLog.w("ParkingSpot: GPS unavailable for the trip: ${e.message}")
            }
        }
    }

    /** A session too short to count as a trip: nothing to capture, just let the GPS go. */
    fun stopTracking(context: Context) {
        val app = context.applicationContext
        handler.post { releaseTracker(app); lastTracked = null }
    }

    /**
     * Mid-trip: write where the unit is now, so a trip cut short by a dead battery or a killed
     * process still leaves a spot no older than the last checkpoint.
     */
    fun checkpoint(context: Context) {
        val app = context.applicationContext
        handler.post { lastTracked?.let { save(app, it) } }
    }

    private fun releaseTracker(app: Context) {
        val t = tracker ?: return
        tracker = null
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try { lm.removeUpdates(t) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun capture(context: Context) {
        val app = context.applicationContext
        handler.post {
            releaseTracker(app)
            val lastTrip = lastTracked
            val tracked = lastTrip?.takeIf { System.currentTimeMillis() - it.time <= LAST_KNOWN_MAX_AGE_MS }
            lastTracked = null
            if (!hasPermission(app)) {
                AppLog.w("ParkingSpot: no location permission, not saving where the trip ended")
                return@post
            }
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@post
            // The newest trip fix, not the most accurate: an older sharper one is further back up the road.
            // An old trip fix still beats none: the GPS goes quiet once the car is parked under a
            // roof, and with airplane mode on there is no network fix either. Being from this very
            // trip, its last fix is where the car stopped, however long the session ran on after.
            val seed = tracked ?: freshLastKnown(lm) ?: lastTrip
            // Written at once: the app is often closed seconds after a trip, long before the
            // listen below ends, and a spot held only in memory is lost with the process.
            seed?.let {
                save(app, it)
                val ageS = (System.currentTimeMillis() - it.time) / 1000
                AppLog.i("ParkingSpot: saved the trip's last fix, accuracy ${it.accuracy.toInt()} m, ${ageS}s old")
            }
            if (listener != null) {
                // A listen from a session that ended moments ago is still running. It carries on,
                // now refining this newer end rather than the older one.
                if (seed != null) { best = seed; bestIsTripFix = true }
                return@post
            }
            best = seed
            bestIsTripFix = seed != null
            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val good = location.accuracy <= GOOD_ACCURACY_M
                    // A good fix from the stop itself beats the trip's last one however sharp that
                    // was: it was taken up to a tracking interval earlier, still rolling.
                    if (best == null || location.accuracy < best!!.accuracy || (bestIsTripFix && good)) {
                        best = location
                        bestIsTripFix = false
                    }
                    if (good) finish(app, lm)
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
            if (!started) {
                finish(app, lm)
                return@post
            }
            // The deadline below counts only time awake. Unheld, a unit that dozes with its screen
            // off stops the clock and leaves the GPS request standing until it next wakes.
            captureLock = try {
                (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenHeadunit::ParkingSpot")
                    ?.apply { setReferenceCounted(false); acquire(MAX_WAIT_MS + 5_000L) }
            } catch (e: Exception) {
                null
            }
            val deadline = Runnable { finish(app, lm) }
            timeout = deadline
            handler.postDelayed(deadline, MAX_WAIT_MS)
        }
    }

    private fun finish(context: Context, lm: LocationManager) {
        val l = listener ?: return
        listener = null
        // Only the deadline: a capture or a tracking start queued behind this must still run.
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
        try { captureLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        captureLock = null
        bestIsTripFix = false
        try { lm.removeUpdates(l) } catch (_: Exception) {}
        val fix = best
        best = null
        if (fix == null) {
            AppLog.w("ParkingSpot: no fix when the trip ended, keeping the previous spot")
            return
        }
        save(context, fix)
        AppLog.i("ParkingSpot: saved, accuracy ${fix.accuracy.toInt()} m")
    }

    private fun save(context: Context, fix: Location) {
        prefs(context).edit()
            .putString(KEY_LAT, fix.latitude.toString())
            .putString(KEY_LON, fix.longitude.toString())
            .putFloat(KEY_ACC, fix.accuracy)
            .putLong(KEY_TIME, fixTime(fix))
            .apply()
    }

    /** When the fix was taken (the parked time shown), unless its clock is plainly wrong. */
    private fun fixTime(fix: Location): Long {
        val now = System.currentTimeMillis()
        return fix.time.takeIf { it > 0 && it <= now + 60_000L && now - it <= 24 * 3_600_000L } ?: now
    }

    private fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

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
