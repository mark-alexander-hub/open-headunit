package com.andrerinas.openheadunit.utils

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the processor running for as long as a projection session is up.
 *
 * With the screen on the display's own lock does that. Once the screen goes dark - the power key
 * on a unit that is really a phone - nothing did: the unit dozed on battery, the link starved, and
 * waking the screen found the session gone instead of the map. Holding this for the session keeps
 * the link alive under a dark screen, so a wake only has to redraw.
 *
 * It costs nothing while the screen is on, and it ends with the session, which ends on its own
 * once the phone walks away. The timeout is only a backstop against a missed release.
 */
object SessionWakeLock {

    private const val TAG = "OpenHeadunit::Session"
    private const val MAX_HELD_MS = 12 * 60 * 60 * 1000L

    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun onSessionStarted(context: Context) {
        if (lock?.isHeld == true) return
        try {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
                setReferenceCounted(false)
                acquire(MAX_HELD_MS)
            }
            AppLog.i("SessionWakeLock: held, so a dark screen does not put the unit to sleep under the session")
        } catch (e: Exception) {
            AppLog.w("SessionWakeLock: could not be taken: ${e.message}")
        }
    }

    @Synchronized
    fun onSessionEnded() {
        val held = lock ?: return
        lock = null
        try {
            if (held.isHeld) {
                held.release()
                AppLog.i("SessionWakeLock: released, session over")
            }
        } catch (e: Exception) {
            AppLog.w("SessionWakeLock: release failed: ${e.message}")
        }
    }
}
