package com.andrerinas.openheadunit.utils

import android.content.Context
import com.andrerinas.openheadunit.App
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Airplane mode for the length of a session, so a unit whose own SIM has nothing to do in the car
 * stops hunting for signal - in a weak area that radio is one of the largest drains on the battery.
 *
 * Only safe where airplane mode leaves WiFi and Bluetooth alone, since the session runs over WiFi
 * Direct and the car's audio over Bluetooth: [radiosAreSafe] checks `airplane_mode_radios` for
 * that and nothing is switched otherwise. Bluetooth reads that setting at boot, so a unit changed
 * to `cell` needs one restart before it is honoured.
 *
 * Android 10 and later let no app toggle airplane mode, so this takes the same root or local ADB
 * route as [WifiStackRecovery], and asks for root only when the user already granted it.
 */
object ProjectionAirplaneMode {

    private const val ENABLE_COMMAND = "cmd connectivity airplane-mode enable"
    private const val DISABLE_COMMAND = "cmd connectivity airplane-mode disable"

    // Its own scope: the service's is cancelled on destroy, which is exactly when the undo runs.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    fun onSessionStarted(context: Context) {
        val app = context.applicationContext
        scope.launch { lock.withLock { enable(app) } }
    }

    /** Also the recovery path on process start, after a session that never ended cleanly. */
    fun onSessionEnded(context: Context) {
        val app = context.applicationContext
        scope.launch { lock.withLock { restore(app) } }
    }

    private suspend fun enable(context: Context) {
        val settings = App.provide(context).settings
        if (!settings.airplaneModeWhileProjecting || settings.airplaneModeSetBySession) return
        if (isAirplaneModeOn(context)) return // the user's own choice; theirs to undo
        if (!radiosAreSafe(context)) {
            AppLog.w("ProjectionAirplaneMode: airplane mode here also switches WiFi or Bluetooth, leaving it off.")
            return
        }
        if (!ensureRoute(context)) return
        // Flag first: if the process dies between the command and the flag, the radio stays off
        // with nothing left to restore it.
        settings.airplaneModeSetBySession = true
        val (code, out) = run(context, ENABLE_COMMAND)
        if (code == 0 && isAirplaneModeOn(context)) {
            AppLog.i("ProjectionAirplaneMode: airplane mode on for the session.")
        } else {
            settings.airplaneModeSetBySession = false
            AppLog.w("ProjectionAirplaneMode: could not turn airplane mode on (code=$code): ${out.take(200)}")
        }
    }

    private suspend fun restore(context: Context) {
        val settings = App.provide(context).settings
        if (!settings.airplaneModeSetBySession) return
        if (!ensureRoute(context)) return
        val (code, out) = run(context, DISABLE_COMMAND)
        if (code == 0 && !isAirplaneModeOn(context)) {
            settings.airplaneModeSetBySession = false
            AppLog.i("ProjectionAirplaneMode: airplane mode off, session over.")
        } else {
            AppLog.w("ProjectionAirplaneMode: could not turn airplane mode off (code=$code): ${out.take(200)}")
        }
    }

    private suspend fun ensureRoute(context: Context): Boolean {
        if (OemAppManager.checkAvailableBackend() != OemAppManager.ExecutionBackend.NONE) return true
        val rootGrantedBefore = App.provide(context).settings.wifiRestartRootGranted
        if (rootGrantedBefore && Shell.isAppGrantedRoot() == null && WifiStackRecovery.requestRoot()) return true
        AppLog.w("ProjectionAirplaneMode: no root and no local ADB, cannot switch airplane mode.")
        return false
    }

    private suspend fun run(context: Context, command: String): Pair<Int, String> = try {
        OemAppManager.executeCommand(context, command)
    } catch (e: Exception) {
        -1 to (e.message ?: e.javaClass.simpleName)
    }

    private fun isAirplaneModeOn(context: Context): Boolean =
        android.provider.Settings.Global.getInt(
            context.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0
        ) != 0

    /** True when airplane mode spares both WiFi and Bluetooth. Unset means Android's default: all. */
    fun radiosAreSafe(context: Context): Boolean {
        val radios = android.provider.Settings.Global.getString(
            context.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_RADIOS
        ) ?: return false
        val list = radios.split(',').map { it.trim() }
        return "wifi" !in list && "bluetooth" !in list
    }
}
