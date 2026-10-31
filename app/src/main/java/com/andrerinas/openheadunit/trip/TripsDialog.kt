package com.andrerinas.openheadunit.trip

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The trip reports and the parking spot, read on the unit itself: one card per trip. */
object TripsDialog {

    private const val SHOWN_TRIPS = 10

    private const val CARD = 0xFF262A30.toInt()
    private const val TEXT = 0xFFECEFF1.toInt()
    private const val MUTED = 0xFF9EA7B0.toInt()
    private const val BATTERY = 0xFF66BB6A.toInt()
    private const val GAP = 0xFFE53935.toInt()
    private const val HOT_MILLI_C = 45_000

    fun show(activity: Activity) {
        val spot = ParkingSpot.load(activity)
        val trips = TripLog.load(activity).take(SHOWN_TRIPS)

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = activity.dp(16)
            setPadding(pad, activity.dp(8), pad, 0)
        }
        if (spot != null) list.addView(parkingCard(activity, spot))
        if (trips.isEmpty()) {
            list.addView(text(activity, activity.getString(R.string.trips_empty), MUTED, 15f))
        }
        trips.forEach { list.addView(tripCard(activity, it)) }

        val builder = MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
            .setTitle(R.string.trips_title)
            .setView(ScrollView(activity).apply { addView(list) })
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

    private fun parkingCard(activity: Activity, spot: ParkingSpot.Spot): View {
        val body = column(activity)
        body.addView(text(activity, activity.getString(R.string.trips_parked) + "  " + dayTime(spot.timeMs) +
            "  ±" + spot.accuracyM.toInt() + " m", TEXT, 16f, bold = true))
        val map = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1B1E22.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(140))
                .apply { topMargin = activity.dp(8) }
        }
        val note = text(activity, activity.getString(R.string.trips_map_loading), MUTED, 12f)
        body.addView(map)
        body.addView(note)
        ParkingMap.load(activity, spot) { bitmap ->
            if (bitmap != null) {
                map.setImageBitmap(bitmap)
                note.text = "© OpenStreetMap contributors"
            } else {
                map.visibility = View.GONE
                note.setText(R.string.trips_map_offline)
            }
        }
        return card(activity, body)
    }

    private fun tripCard(activity: Activity, trip: TripLog.Trip): View {
        val body = column(activity)
        body.addView(text(activity, dayTime(trip.startMs) + " – " + time(trip.endMs) + "  ·  " +
            minutes(trip.durationMs), TEXT, 16f, bold = true))
        if (trip.battery.size >= 2 || trip.gaps.isNotEmpty()) {
            body.addView(TripChart(activity, trip).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(64))
                    .apply { topMargin = activity.dp(8); bottomMargin = activity.dp(4) }
            })
        }
        if (trip.batteryStart >= 0 && trip.batteryEnd >= 0) {
            val perHour = trip.batteryPerHour?.let { "  (" + String.format(Locale.US, "%.0f", it) + " %/h)" } ?: ""
            body.addView(text(activity, "Battery " + trip.batteryStart + " → " + trip.batteryEnd + " %" + perHour, BATTERY, 14f))
        }
        if (trip.gaps.isEmpty()) {
            body.addView(text(activity, "Music drops: none", MUTED, 14f))
        } else {
            body.addView(text(activity, "Music drops: " + trip.gaps.size + "  —  " +
                trip.gaps.joinToString(", ") { time(it.startMs) + " (" + seconds(it.durationMs) + ")" }, GAP, 14f))
        }
        body.addView(text(activity, "Link drops: " + trip.linkDrops, if (trip.linkDrops > 0) GAP else MUTED, 14f))
        mapUp(activity, trip)?.let { body.addView(text(activity, it, MUTED, 14f)) }
        steps(trip)?.let { body.addView(text(activity, it, MUTED, 13f)) }
        heat(trip)?.let { (line, hot) -> body.addView(text(activity, line, if (hot) GAP else MUTED, 14f)) }
        return card(activity, body)
    }

    /** "WiFi restart 0.9 s → link up 6.8 s → phone BT 7.9 s → phone on WiFi 12.3 s", in time order. */
    private fun steps(trip: TripLog.Trip): String? {
        if (trip.steps.isEmpty()) return null
        return trip.steps.entries.sortedBy { it.value }.joinToString(" → ") { (step, ms) ->
            when (step) {
                TripLog.Step.WIFI_RESTART -> "WiFi restart"
                TripLog.Step.GROUP_UP -> "link up"
                TripLog.Step.PHONE_BLUETOOTH -> "phone BT"
                TripLog.Step.PHONE_ON_WIFI -> "phone on WiFi"
            } + " " + seconds1(ms)
        }
    }

    private fun seconds1(ms: Long) = String.format(Locale.US, "%.1f s", ms / 1000.0)

    /** "Hottest: CPU 52 °C · battery 38 °C", red from [HOT_MILLI_C]; null when nothing was read. */
    private fun heat(trip: TripLog.Trip): Pair<String, Boolean>? {
        val parts = listOfNotNull(
            trip.cpuMaxMilliC.takeIf { it >= 0 }?.let { "CPU " + (it + 500) / 1000 + " °C" },
            trip.batteryMaxMilliC.takeIf { it >= 0 }?.let { "battery " + (it + 500) / 1000 + " °C" }
        )
        if (parts.isEmpty()) return null
        val hot = trip.cpuMaxMilliC >= HOT_MILLI_C || trip.batteryMaxMilliC >= HOT_MILLI_C
        return "Hottest: " + parts.joinToString("  ·  ") to hot
    }

    private fun mapUp(context: Context, trip: TripLog.Trip): String? = when {
        trip.connectMs >= 0 && trip.videoMs >= 0 -> context.getString(R.string.trips_map_up_split,
            seconds(trip.connectMs + trip.videoMs), seconds(trip.connectMs), seconds(trip.videoMs))
        trip.videoMs >= 0 -> context.getString(R.string.trips_map_up, seconds(trip.videoMs))
        else -> null
    }

    /** Battery level over the trip in green, every music drop as a red band at its time. */
    private class TripChart(context: Context, private val trip: TripLog.Trip) : View(context) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BATTERY; style = Paint.Style.STROKE; strokeWidth = context.dp(2).toFloat()
            strokeJoin = Paint.Join.ROUND
        }
        private val gap = Paint().apply { color = GAP; alpha = 150 }
        private val axis = Paint().apply { color = 0xFF3A4048.toInt(); strokeWidth = 1f }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = context.dp(10).toFloat() }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val span = (trip.endMs - trip.startMs).coerceAtLeast(1L).toFloat()
            fun x(ms: Long) = ((ms - trip.startMs) / span).coerceIn(0f, 1f) * w
            canvas.drawLine(0f, h - 1, w, h - 1, axis)
            val minGapPx = context.dp(2).toFloat()
            trip.gaps.forEach {
                val left = x(it.startMs)
                canvas.drawRect(left, 0f, maxOf(x(it.startMs + it.durationMs), left + minGapPx), h, gap)
            }
            val samples = trip.battery
            if (samples.size < 2) return
            // Scaled to the trip's own range so a 5 % drop still shows a slope.
            val top = samples.maxOf { it.percent }
            val bottom = minOf(samples.minOf { it.percent }, top - 5)
            val pad = context.dp(4).toFloat()
            fun y(p: Int) = pad + (top - p).toFloat() / (top - bottom) * (h - 2 * pad)
            val path = Path()
            samples.forEachIndexed { i, s ->
                if (i == 0) path.moveTo(x(s.atMs), y(s.percent)) else path.lineTo(x(s.atMs), y(s.percent))
            }
            canvas.drawPath(path, line)
            // Top right: the line starts high on the left and falls away from here.
            canvas.drawText("$top %", w - label.measureText("$top %") - pad, label.textSize, label)
        }
    }

    private fun card(context: Context, content: View) = MaterialCardView(context).apply {
        setCardBackgroundColor(CARD)
        radius = context.dp(12).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(10) }
        addView(content)
    }

    private fun column(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = context.dp(14)
        setPadding(pad, context.dp(12), pad, context.dp(12))
    }

    private fun text(context: Context, value: String, color: Int, sp: Float, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, context.dp(2), 0, context.dp(2))
        }

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun dayTime(ms: Long) = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ms))
    private fun time(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    private fun minutes(ms: Long) = "${ms / 60_000} min"
    private fun seconds(ms: Long): String {
        val s = (ms + 500) / 1000
        return if (s < 60) "$s s" else "${s / 60}m ${s % 60}s"
    }
}
