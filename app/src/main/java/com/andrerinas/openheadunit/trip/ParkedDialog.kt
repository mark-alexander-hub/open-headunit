package com.andrerinas.openheadunit.trip

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.ProjectionAirplaneMode
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Something to watch while parked: one tile per video app already installed on this unit.
 *
 * A unit that is really a phone can play video by itself; Android Auto cannot be made to. So this
 * only opens the unit's own apps, and only from the home screen, which a projecting session
 * covers - there is no way to reach it over the map. Sound goes wherever the unit's sound goes.
 */
object ParkedDialog {

    /** Checked in this order; only the ones installed are shown. */
    private val VIDEO_APPS = listOf(
        "com.google.android.youtube",
        "org.jellyfin.mobile",
        "org.videolan.vlc",
        "com.netflix.mediaclient",
        "in.startv.hotstar",
        "com.amazon.avod.thirdpartyclient",
        "com.mxtech.videoplayer.ad",
        "org.schabi.newpipe",
        "org.xbmc.kodi",
    )

    private const val CARD = 0xFF262A30.toInt()
    private const val TEXT = 0xFFECEFF1.toInt()
    private const val MUTED = 0xFF9EA7B0.toInt()

    private class App(val label: String, val icon: android.graphics.drawable.Drawable?, val launch: Intent)

    /** @param sessionUp a projection session is live; nothing is opened over it. */
    fun show(activity: Activity, sessionUp: Boolean) {
        if (sessionUp) {
            ToastUtils.showToast(activity, R.string.parked_busy, Toast.LENGTH_LONG, force = true)
            return
        }
        // Opening this is the user using the unit, not a unit left behind after a trip.
        TripSleep.cancel()
        // A stream needs the unit's own data, which a session's airplane mode may still be holding.
        ProjectionAirplaneMode.restoreNow(activity)
        val apps = installed(activity)

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = activity.dp(16)
            setPadding(pad, activity.dp(8), pad, 0)
        }
        val dialog = MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
            .setTitle(R.string.parked_title)
            .setView(column)
            .setNegativeButton(R.string.parked_back, null)
            .create()

        if (apps.isEmpty()) {
            column.addView(text(activity, activity.getString(R.string.parked_none), MUTED, 15f))
        } else {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            apps.forEach { app ->
                row.addView(tile(activity, app) {
                    try {
                        // Before the app opens: the head unit woke the phone when it was opened,
                        // and the session that follows would cover the video a second later.
                        ParkedMode.enter(activity)
                        activity.startActivity(app.launch)
                        AppLog.i("ParkedDialog: opened ${app.label}")
                        dialog.dismiss()
                        standDown(activity)
                    } catch (e: Exception) {
                        ParkedMode.leave(activity, "the app did not open")
                        AppLog.w("ParkedDialog: could not open ${app.label}: ${e.message}")
                        ToastUtils.showToast(activity, R.string.parked_failed, Toast.LENGTH_LONG, force = true)
                    }
                })
            }
            column.addView(HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
        }
        column.addView(text(activity, status(activity), MUTED, 13f).apply {
            setPadding(0, activity.dp(10), 0, 0)
        })
        dialog.show()
    }

    /** Stops the head unit the way its Exit button does, so nothing is left listening for the phone. */
    private fun standDown(activity: Activity) {
        try {
            ContextCompat.startForegroundService(
                activity, Intent(activity, AapService::class.java).setAction(AapService.ACTION_STOP_SERVICE)
            )
        } catch (e: Exception) {
            AppLog.w("ParkedDialog: could not stop the head unit: ${e.message}")
        }
    }

    private fun installed(context: Context): List<App> {
        val pm = context.packageManager
        return VIDEO_APPS.mapNotNull { pkg ->
            try {
                val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val info = pm.getApplicationInfo(pkg, 0)
                App(pm.getApplicationLabel(info).toString(), try { pm.getApplicationIcon(info) } catch (_: Exception) { null }, launch)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun tile(context: Context, app: App, onClick: () -> Unit): View {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = context.dp(14)
            setPadding(pad, pad, pad, context.dp(10))
            addView(ImageView(context).apply {
                setImageDrawable(app.icon)
                layoutParams = LinearLayout.LayoutParams(context.dp(64), context.dp(64))
            })
            addView(text(context, app.label, TEXT, 15f, bold = true).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 1
                setPadding(0, context.dp(8), 0, 0)
            })
        }
        return MaterialCardView(context).apply {
            setCardBackgroundColor(CARD)
            radius = context.dp(12).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(context.dp(132), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = context.dp(12) }
            addView(body)
            setOnClickListener { onClick() }
        }
    }

    /** "Battery 78 %  ·  Internet: mobile data" - what decides whether a stream will play. */
    private fun status(context: Context): String {
        val net = context.getString(when (activeNetworkType(context)) {
            ConnectivityManager.TYPE_WIFI -> R.string.parked_net_wifi
            ConnectivityManager.TYPE_MOBILE -> R.string.parked_net_mobile
            else -> R.string.parked_net_none
        })
        val battery = batteryPercent(context)
        return if (battery < 0) context.getString(R.string.parked_status_net, net)
        else context.getString(R.string.parked_status, battery, net)
    }

    @Suppress("DEPRECATION")
    private fun activeNetworkType(context: Context): Int = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val info = cm?.activeNetworkInfo
        if (info != null && info.isConnected) info.type else -1
    } catch (e: Exception) {
        -1
    }

    private fun batteryPercent(context: Context): Int = try {
        val intent = context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) -1 else level * 100 / scale
    } catch (e: Exception) {
        -1
    }

    private fun text(context: Context, value: String, color: Int, sp: Float, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
