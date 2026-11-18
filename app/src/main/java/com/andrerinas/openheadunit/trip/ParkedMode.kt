package com.andrerinas.openheadunit.trip

import android.content.Context
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ScreenPower

/** When a Bluetooth connect from the phone is left unanswered because the user is watching something. */
object ParkedPolicy {

    /** Long enough for a film; after it a forgotten hold cannot keep the next drive from starting. */
    const val MAX_HOLD_MS = 3 * 60 * 60 * 1000L

    /**
     * @param ageMs since a Parked app was opened, on a clock that restarts with the unit, so a
     *   reboot reads as negative and ends the hold.
     * @param screenOn null when it cannot be read. Only a lit screen holds: a dark one is a unit
     *   nobody is watching, and the phone arriving then is a drive beginning.
     */
    fun holdsAutoStart(ageMs: Long, screenOn: Boolean?): Boolean =
        ageMs in 0 until MAX_HOLD_MS && screenOn == true
}

/**
 * Keeps Android Auto from taking the screen back while a Parked app is open.
 *
 * Opening the head unit wakes the phone, and the phone then starts a session over whatever is on
 * the screen - which, a second after a Parked tile is tapped, is the video. So the tile stops the
 * head unit and raises this hold; the phone's Bluetooth connecting is then left alone while the
 * screen is lit. Coming back to the head unit's own screen ends it, and so does a dark screen.
 */
object ParkedMode {

    private const val PREFS = "trip_log"
    private const val KEY_SINCE = "parked-since"

    fun enter(context: Context) {
        // commit: the head unit is stopped straight after this and its process may go with it.
        prefs(context).edit().putLong(KEY_SINCE, SystemClock.elapsedRealtime()).commit()
        AppLog.i("ParkedMode: on - Android Auto stays asleep until the head unit is opened again")
    }

    fun leave(context: Context, why: String) {
        val p = prefs(context)
        if (!p.contains(KEY_SINCE)) return
        p.edit().remove(KEY_SINCE).commit()
        AppLog.i("ParkedMode: off, $why")
    }

    /** Asked when the phone's Bluetooth connects. Ends a hold that no longer applies. */
    fun holdsAutoStart(context: Context): Boolean {
        val since = prefs(context).getLong(KEY_SINCE, -1L)
        if (since < 0) return false
        val hold = ParkedPolicy.holdsAutoStart(
            SystemClock.elapsedRealtime() - since, ScreenPower.isInteractive(context)
        )
        if (!hold) leave(context, "the screen is dark or the hold ran out")
        return hold
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
