package com.andrerinas.openheadunit.trip

import android.app.Activity
import android.content.ActivityNotFoundException
import android.widget.Toast
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The trip reports and the parking spot, read on the unit itself. */
object TripsDialog {

    private const val SHOWN_TRIPS = 10

    fun show(activity: Activity) {
        val spot = ParkingSpot.load(activity)
        val trips = TripLog.load(activity).take(SHOWN_TRIPS)
        val text = buildString {
            if (spot != null) {
                append(activity.getString(R.string.trips_parked)).append(": ")
                append(dayTime(spot.timeMs)).append(" (±").append(spot.accuracyM.toInt()).append(" m)\n\n")
            }
            if (trips.isEmpty()) append(activity.getString(R.string.trips_empty))
            trips.forEach { append(describe(it)).append("\n\n") }
        }.trimEnd()

        val builder = MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
            .setTitle(R.string.trips_title)
            .setMessage(text)
            .setNegativeButton(android.R.string.ok, null)
        if (spot != null) {
            builder.setPositiveButton(R.string.trips_where_car) { _, _ ->
                try {
                    activity.startActivity(ParkingSpot.mapIntent(spot))
                } catch (e: ActivityNotFoundException) {
                    ToastUtils.showToast(activity, R.string.trips_no_maps, Toast.LENGTH_LONG, force = true)
                }
            }
        }
        if (trips.isNotEmpty()) {
            builder.setNeutralButton(R.string.trips_clear) { _, _ -> TripLog.clear(activity) }
        }
        builder.show()
    }

    private fun describe(trip: TripLog.Trip): String = buildString {
        append(dayTime(trip.startMs)).append(" – ").append(time(trip.endMs))
        append(" · ").append(minutes(trip.durationMs)).append('\n')
        if (trip.batteryStart >= 0 && trip.batteryEnd >= 0) {
            append("Battery ").append(trip.batteryStart).append(" → ").append(trip.batteryEnd).append(" %")
            trip.batteryPerHour?.let { append(" (").append(String.format(Locale.US, "%.0f", it)).append(" %/h)") }
            append('\n')
        }
        append("Link drops: ").append(trip.linkDrops).append('\n')
        if (trip.gaps.isEmpty()) {
            append("Music gaps: none")
        } else {
            append("Music gaps: ").append(trip.gaps.size).append(" — ")
            append(trip.gaps.joinToString(", ") { time(it.startMs) + " (" + seconds(it.durationMs) + ")" })
        }
    }

    private fun dayTime(ms: Long) = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ms))
    private fun time(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    private fun minutes(ms: Long) = "${ms / 60_000} min"
    private fun seconds(ms: Long): String {
        val s = ms / 1000
        return if (s < 60) "$s s" else "${s / 60}m ${s % 60}s"
    }
}
