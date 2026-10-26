package com.andrerinas.openheadunit.trip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.utils.AppLog
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * A small street map around the parking spot, for the Trips card. Nine OpenStreetMap tiles are
 * fetched once per spot and the result kept in the cache, so it shows again without internet;
 * a car park usually has none, so it is fetched whenever the card is first opened with a network.
 */
internal object ParkingMap {

    private const val ZOOM = 17
    private const val TILE = 256
    private const val WIDTH = 640
    private const val HEIGHT = 300
    private const val TIMEOUT_MS = 6_000
    private const val USER_AGENT = "OpenHeadunit-ParkingSpot/1.0 (personal head unit)"

    private val main = Handler(Looper.getMainLooper())

    fun load(context: Context, spot: ParkingSpot.Spot, done: (Bitmap?) -> Unit) {
        val app = context.applicationContext
        thread(name = "ParkingMap") {
            val bitmap = try {
                cached(app, spot) ?: fetch(spot)?.also { save(app, spot, it) }
            } catch (e: Exception) {
                AppLog.w("ParkingMap: no map, ${e.message}")
                null
            }
            main.post { done(bitmap) }
        }
    }

    private fun file(context: Context, spot: ParkingSpot.Spot) =
        File(context.cacheDir, "parking-map-${spot.timeMs}.png")

    private fun cached(context: Context, spot: ParkingSpot.Spot): Bitmap? =
        file(context, spot).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    private fun save(context: Context, spot: ParkingSpot.Spot, bitmap: Bitmap) {
        // One spot at a time: the previous one's map is of no use any more.
        context.cacheDir.listFiles { f -> f.name.startsWith("parking-map-") }?.forEach { it.delete() }
        FileOutputStream(file(context, spot)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun fetch(spot: ParkingSpot.Spot): Bitmap? {
        val scale = TILE * (1 shl ZOOM).toDouble()
        val px = (spot.longitude + 180.0) / 360.0 * scale
        val latRad = spot.latitude * PI / 180.0
        val py = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * scale
        val left = px - WIDTH / 2.0
        val top = py - HEIGHT / 2.0

        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val firstX = floor(left / TILE).toInt()
        val firstY = floor(top / TILE).toInt()
        val lastX = floor((left + WIDTH) / TILE).toInt()
        val lastY = floor((top + HEIGHT) / TILE).toInt()
        for (tx in firstX..lastX) for (ty in firstY..lastY) {
            val tile = tile(tx, ty) ?: return null
            canvas.drawBitmap(tile, (tx * TILE - left).toFloat(), (ty * TILE - top).toFloat(), null)
            tile.recycle()
        }

        val cx = WIDTH / 2f
        val cy = HEIGHT / 2f
        // Accuracy circle at this zoom's metres per pixel, then the pin.
        val metresPerPx = 156543.03 * cos(latRad) / (1 shl ZOOM)
        val radius = (spot.accuracyM / metresPerPx).toFloat().coerceIn(8f, 120f)
        canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332196F3 })
        canvas.drawCircle(cx, cy, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        canvas.drawCircle(cx, cy, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() })
        return out
    }

    private fun tile(x: Int, y: Int): Bitmap? {
        val conn = URL("https://tile.openstreetmap.org/$ZOOM/$x/$y.png").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode != 200) null else conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }
}
