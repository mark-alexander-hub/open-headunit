package com.andrerinas.openheadunit.trip

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.OemAppManager
import com.andrerinas.openheadunit.utils.ScreenPower
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Screen off once a trip is over, so a unit left in the vehicle does not sit lit until its battery
 * is gone. Opt-in, and deliberately hard to trigger by mistake - all of these must hold
 * [DELAY_MS] after the session ended:
 *
 * - it ended on its own, not by the user's Exit (who is then using the unit as a phone);
 * - no new session started, and the auto-start phone's Bluetooth is gone - a WiFi Direct drop
 *   mid-drive keeps the phone connected, so it never blanks the screen while driving;
 * - this app is still in front, untouched since the end, and the screen is still on.
 *
 * Android lets no app turn the screen off without device-admin, so it takes the root or local ADB
 * route, like [com.andrerinas.openheadunit.utils.ProjectionAirplaneMode].
 */
object TripSleep {

    private const val DELAY_MS = 60_000L
    private const val SLEEP_COMMAND = "input keyevent 223" // KEYCODE_SLEEP

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var endedAt = 0L
    @Volatile private var lastTouch = 0L
    @Volatile private var resumedActivities = 0

    fun onSessionEnded(context: Context, userExit: Boolean) {
        cancel()
        val app = context.applicationContext
        if (userExit || !App.provide(app).settings.sleepAfterTrip) return
        endedAt = SystemClock.elapsedRealtime()
        handler.postDelayed({ maybeSleep(app) }, DELAY_MS)
    }

    fun cancel() = handler.removeCallbacksAndMessages(null)

    /** From every activity of this app, so a touch after the trip keeps the screen on. */
    fun noteUserInteraction() {
        lastTouch = SystemClock.elapsedRealtime()
    }

    fun noteActivityResumed() { resumedActivities++ }
    fun noteActivityPaused() { if (resumedActivities > 0) resumedActivities-- }

    private fun maybeSleep(context: Context) {
        val why = when {
            resumedActivities == 0 -> "another app is in front"
            lastTouch >= endedAt -> "the screen was touched after the trip"
            ScreenPower.isInteractive(context) != true -> "the screen is already off"
            phoneStillConnected(context) -> "the phone is still connected over Bluetooth"
            else -> null
        }
        if (why != null) {
            AppLog.i("TripSleep: leaving the screen on, $why")
            return
        }
        scope.launch {
            if (OemAppManager.checkAvailableBackend() == OemAppManager.ExecutionBackend.NONE) {
                AppLog.w("TripSleep: no root and no local ADB, cannot turn the screen off")
                return@launch
            }
            val (code, out) = try {
                OemAppManager.executeCommand(context, SLEEP_COMMAND)
            } catch (e: Exception) {
                -1 to (e.message ?: e.javaClass.simpleName)
            }
            if (code == 0) AppLog.i("TripSleep: trip over, screen off")
            else AppLog.w("TripSleep: screen off failed (code=$code): ${out.take(200)}")
        }
    }

    /** True also when it cannot be read: unsure means leave the screen on. */
    private fun phoneStillConnected(context: Context): Boolean {
        val macs = App.provide(context).settings.autoStartBluetoothDeviceMacs
        if (macs.isEmpty()) return true
        return try {
            @Suppress("DEPRECATION")
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return true
            if (!adapter.isEnabled) return false
            macs.any { BluetoothHelper.deviceConnectionState(adapter.getRemoteDevice(it)) != false }
        } catch (e: Exception) {
            true
        }
    }
}
