package com.andrerinas.openheadunit.trip

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * A short record of each drive or ride, kept on this unit so it can be read without a computer:
 * when it ran, the battery it used, how often the link to the phone dropped, and every time the
 * phone stopped sending music mid-session - a gap the head unit cannot fix but can at least date.
 *
 * A session that comes back within [RESUME_WINDOW_MS] of ending continues the same trip, so a
 * WiFi Direct drop and its reconnect read as one trip with one link drop, not two short trips.
 * Also the session hook for [ParkingSpot] and [TripSleep].
 *
 * Each trip also keeps a battery sample every [BATTERY_SAMPLE_MS] for the graph in [TripsDialog],
 * and how long the map took to come up: from the unit being woken or the app opened to the phone
 * connecting, and from there to the first video frame.
 */
object TripLog {

    private const val PREFS = "trip_log"
    private const val KEY_TRIPS = "trips"
    private const val MAX_TRIPS = 30
    private const val RESUME_WINDOW_MS = 3 * 60_000L
    /** Shorter than this is a track change, not a drop. */
    private const val MIN_GAP_MS = 3_000L
    /** Shorter than this is a failed or test connection: no trip, no parking spot. */
    private const val MIN_TRIP_MS = 2 * 60_000L
    private const val BATTERY_SAMPLE_MS = 5 * 60_000L
    /** A wait longer than this was the unit sitting idle, not a slow connection. */
    private const val MAX_CONNECT_WAIT_MS = 5 * 60_000L
    private const val LOW_BATTERY_PERCENT = 25

    data class MusicGap(val startMs: Long, val durationMs: Long)

    data class BatterySample(val atMs: Long, val percent: Int)

    data class Trip(
        val startMs: Long,
        val endMs: Long,
        val batteryStart: Int,
        val batteryEnd: Int,
        val linkDrops: Int,
        val gaps: List<MusicGap>,
        val battery: List<BatterySample> = emptyList(),
        /** Woken or opened to the phone connected, or -1 when unknown. */
        val connectMs: Long = -1,
        /** Phone connected to the first frame of the map, or -1 when unknown. */
        val videoMs: Long = -1,
        /** Hottest CPU and battery during the trip in milli-degrees C, or -1 when unread. See [TripHeat]. */
        val cpuMaxMilliC: Int = -1,
        val batteryMaxMilliC: Int = -1
    ) {
        val durationMs: Long get() = endMs - startMs

        /** Battery points per hour, or null when unreadable or too short to mean anything. */
        val batteryPerHour: Double?
            get() = if (batteryStart < 0 || batteryEnd < 0 || durationMs < 10 * 60_000L) null
            else (batteryStart - batteryEnd) * 3_600_000.0 / durationMs

        fun toJson(): JSONObject = JSONObject().apply {
            put("start", startMs)
            put("end", endMs)
            put("batStart", batteryStart)
            put("batEnd", batteryEnd)
            put("linkDrops", linkDrops)
            put("gaps", JSONArray().apply {
                gaps.forEach { put(JSONObject().put("at", it.startMs).put("ms", it.durationMs)) }
            })
            put("bat", JSONArray().apply {
                battery.forEach { put(JSONArray().put(it.atMs).put(it.percent)) }
            })
            put("connectMs", connectMs)
            put("videoMs", videoMs)
            put("cpuMax", cpuMaxMilliC)
            put("batMax", batteryMaxMilliC)
        }

        companion object {
            fun fromJson(o: JSONObject): Trip {
                val arr = o.optJSONArray("gaps") ?: JSONArray()
                val gaps = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { MusicGap(it.optLong("at"), it.optLong("ms")) }
                }
                val bat = o.optJSONArray("bat") ?: JSONArray()
                val battery = (0 until bat.length()).mapNotNull { i ->
                    bat.optJSONArray(i)?.let { BatterySample(it.optLong(0), it.optInt(1)) }
                }
                return Trip(o.optLong("start"), o.optLong("end"), o.optInt("batStart", -1),
                    o.optInt("batEnd", -1), o.optInt("linkDrops"), gaps, battery,
                    o.optLong("connectMs", -1), o.optLong("videoMs", -1),
                    o.optInt("cpuMax", -1), o.optInt("batMax", -1))
            }
        }
    }

    private class Live(val startMs: Long, val batteryStart: Int, val linkDrops: Int, gaps: List<MusicGap>,
                       battery: List<BatterySample>, var connectMs: Long, var videoMs: Long,
                       val cpuMaxSoFar: Int = -1, val batteryMaxSoFar: Int = -1) {
        val gaps = gaps.toMutableList()
        val battery = battery.toMutableList()
        var musicPlaying = false
        var musicStoppedAt = -1L
        /** Elapsed-realtime of the connect, until the first frame is seen. */
        var connectedAt = -1L
        /** Continues the newest saved trip, which stays saved until this one replaces it. */
        var resumed = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var live: Live? = null
    private var sessionActive = false
    /** Elapsed-realtime of the app opening, the screen coming on or the last session ending. */
    @Volatile private var waitingSince = SystemClock.elapsedRealtime()

    /** From the app's start and the screen-on broadcast: the moment a connection is wanted. */
    fun noteWaitingStarted() {
        if (!sessionActive) waitingSince = SystemClock.elapsedRealtime()
    }

    /** The first frame of the map after a connect; later calls in the same session are ignored. */
    @Synchronized
    fun onFirstFrame() {
        val trip = live ?: return
        if (trip.connectedAt < 0) return
        trip.videoMs = SystemClock.elapsedRealtime() - trip.connectedAt
        trip.connectedAt = -1L
        AppLog.i("TripLog: map up, connect ${trip.connectMs / 1000.0}s + video ${trip.videoMs / 1000.0}s")
    }

    private fun scheduleBatterySample(context: Context) {
        handler.postDelayed({ sampleBattery(context) }, BATTERY_SAMPLE_MS)
    }

    @Synchronized
    private fun sampleBattery(context: Context) {
        val trip = live ?: return
        val percent = batteryPercent(context)
        if (percent >= 0) trip.battery.add(BatterySample(System.currentTimeMillis(), percent))
        scheduleBatterySample(context)
    }

    @Synchronized
    fun onSessionStarted(context: Context) {
        sessionActive = true
        TripSleep.cancel()
        if (!App.provide(context).settings.tripLog) return
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val trips = load(app)
        val last = trips.firstOrNull()
        live = if (last != null && now - last.endMs in 0..RESUME_WINDOW_MS) {
            AppLog.i("TripLog: session back within ${(now - last.endMs) / 1000}s, continuing the trip")
            // The first connect of the trip is the one worth timing, not a reconnect.
            Live(last.startMs, last.batteryStart, last.linkDrops + 1, last.gaps, last.battery,
                last.connectMs, last.videoMs, last.cpuMaxMilliC, last.batteryMaxMilliC).also { it.resumed = true }
        } else {
            val waited = SystemClock.elapsedRealtime() - waitingSince
            val battery = batteryPercent(app)
            if (battery in 0 until LOW_BATTERY_PERCENT && !isCharging(app)) warnLowBattery(app, battery)
            Live(now, battery, 0, emptyList(),
                if (battery >= 0) listOf(BatterySample(now, battery)) else emptyList(),
                if (waited in 0..MAX_CONNECT_WAIT_MS) waited else -1L, -1L
            ).also { it.connectedAt = SystemClock.elapsedRealtime() }
        }
        handler.removeCallbacksAndMessages(null)
        scheduleBatterySample(app)
        ParkingSpot.startTracking(app)
        live?.let { TripHeat.start(app, it.cpuMaxSoFar, it.batteryMaxSoFar) }
    }

    /**
     * Once, at the start of a new trip (not a reconnect): at ~15 %/h while projecting, a unit below
     * this will not see a long drive through. A toast, so it shows over the map and then goes.
     */
    private fun warnLowBattery(context: Context, percent: Int) {
        AppLog.i("TripLog: battery $percent % at the start of the trip, warning")
        // Not [handler]: the session start clears that one's queue straight after this call.
        Handler(Looper.getMainLooper()).post {
            ToastUtils.showToast(context, context.getString(R.string.trips_low_battery, percent),
                Toast.LENGTH_LONG, force = true)
        }
    }

    private fun isCharging(context: Context): Boolean = try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    } catch (e: Exception) {
        false
    }

    /** The phone started or stopped the media stream. Called from the transport thread. */
    @Synchronized
    fun onMusic(playing: Boolean) {
        val trip = live ?: return
        val now = System.currentTimeMillis()
        if (playing) {
            if (trip.musicStoppedAt > 0 && now - trip.musicStoppedAt >= MIN_GAP_MS) {
                trip.gaps.add(MusicGap(trip.musicStoppedAt, now - trip.musicStoppedAt))
            }
            trip.musicStoppedAt = -1L
            trip.musicPlaying = true
        } else if (trip.musicPlaying) {
            trip.musicPlaying = false
            trip.musicStoppedAt = now
        }
    }

    @Synchronized
    fun onSessionEnded(context: Context, userExit: Boolean) {
        // The service's destroy repeats the end of a session that already ended; only the first counts.
        if (!sessionActive) return
        sessionActive = false
        waitingSince = SystemClock.elapsedRealtime()
        handler.removeCallbacksAndMessages(null)
        TripSleep.onSessionEnded(context, userExit)
        val trip = live ?: return
        live = null
        TripHeat.stop()
        val now = System.currentTimeMillis()
        if (now - trip.startMs < MIN_TRIP_MS) {
            ParkingSpot.stopTracking(context)
            return
        }
        val batteryEnd = batteryPercent(context)
        if (batteryEnd >= 0) trip.battery.add(BatterySample(now, batteryEnd))
        // A stop still open at the end is the music ending with the trip, not a drop.
        val record = Trip(trip.startMs, now, trip.batteryStart, batteryEnd, trip.linkDrops, trip.gaps,
            trip.battery, trip.connectMs, trip.videoMs, TripHeat.cpuMax, TripHeat.batteryMax)
        val saved = load(context).let { if (trip.resumed) it.drop(1) else it }
        save(context, (listOf(record) + saved).take(MAX_TRIPS))
        AppLog.i("TripLog: trip saved, ${record.durationMs / 60_000} min, ${record.gaps.size} music gaps, ${record.linkDrops} link drops")
        ParkingSpot.capture(context)
    }

    /** Newest first. */
    fun load(context: Context): List<Trip> {
        val json = prefs(context).getString(KEY_TRIPS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Trip.fromJson(it) } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) = prefs(context).edit().remove(KEY_TRIPS).apply()

    private fun save(context: Context, trips: List<Trip>) {
        val arr = JSONArray()
        trips.forEach { arr.put(it.toJson()) }
        // commit, not apply: the process can be killed seconds after a session ends.
        prefs(context).edit().putString(KEY_TRIPS, arr.toString()).commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 0-100, or -1 when the battery cannot be read. The sticky broadcast works on every API level. */
    private fun batteryPercent(context: Context): Int = try {
        val intent = context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) -1 else level * 100 / scale
    } catch (e: Exception) {
        -1
    }
}
