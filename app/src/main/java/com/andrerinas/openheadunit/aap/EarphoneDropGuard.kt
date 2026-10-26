package com.andrerinas.openheadunit.aap

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Earphones that drop off mid-session - a USB-C plug shaken loose on a bike - leave the phone
 * playing into this unit's speaker, which may be silent or dead: the music runs on unheard. This
 * pauses the phone the moment the earphones go, and plays again when they come back, but only
 * if it was the one that paused and they return within [RESUME_WINDOW_MS].
 *
 * A loose plug bounces: one shake can remove and re-add the device dozens of times in a few
 * seconds. Play waits until the earphones have stayed connected for [SETTLE_MS].
 */
internal object EarphoneDropGuard {

    private const val SETTLE_MS = 1_500L
    private const val RESUME_WINDOW_MS = 10 * 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private var callback: AudioDeviceCallback? = null
    private var audioManager: AudioManager? = null
    private var sendKey: ((Int) -> Unit)? = null

    @Volatile private var musicPlaying = false
    private var pausedAt = -1L

    /** The phone started or stopped the media stream. Called from the transport thread. */
    fun onMusic(playing: Boolean) {
        musicPlaying = playing
    }

    fun start(context: Context, send: (Int) -> Unit) {
        stop()
        if (Build.VERSION.SDK_INT < 23) return
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am
        sendKey = send
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (pausedAt < 0 || addedDevices.none(::isEarphone)) return
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed(::resumeIfSettled, SETTLE_MS)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.none(::isEarphone)) return
                handler.removeCallbacksAndMessages(null)
                if (EarphoneBoost.earphonesConnected(am)) return // another pair is still in
                if (pausedAt < 0 && musicPlaying) {
                    AppLog.i("EarphoneDropGuard: earphones gone while playing, pausing the phone")
                    press(KeyEvent.KEYCODE_MEDIA_PAUSE)
                    pausedAt = SystemClock.elapsedRealtime()
                }
            }
        }
        am.registerAudioDeviceCallback(cb, handler)
        callback = cb
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= 23) callback?.let { cb ->
            try { audioManager?.unregisterAudioDeviceCallback(cb) } catch (_: Exception) {}
        }
        callback = null
        audioManager = null
        sendKey = null
        pausedAt = -1L
        // A session can end without the phone's media stop; a stale "playing" would let a later
        // unplug and re-plug start music the user never played.
        musicPlaying = false
    }

    private fun resumeIfSettled() {
        val am = audioManager ?: return
        if (pausedAt < 0 || !EarphoneBoost.earphonesConnected(am)) return
        val away = SystemClock.elapsedRealtime() - pausedAt
        pausedAt = -1L
        if (away > RESUME_WINDOW_MS) {
            AppLog.i("EarphoneDropGuard: earphones back after ${away / 1000}s, too long to resume on our own")
            return
        }
        AppLog.i("EarphoneDropGuard: earphones back after ${away / 1000}s, playing again")
        press(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    private fun press(keyCode: Int) {
        sendKey?.invoke(keyCode)
    }

    private fun isEarphone(device: AudioDeviceInfo): Boolean =
        device.isSink && EarphoneBoost.isEarphoneType(device.type)
}
