package com.andrerinas.openheadunit.aap

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Extra gain while the sound goes to earphones, for wind and engine noise on a bike: voice
 * directions stay audible without the user turning every source to full. The mixer's soft
 * limiter takes over above unity gain, so the boost compresses peaks instead of clipping them.
 * The speaker and Bluetooth keep their normal level.
 */
internal object EarphoneBoost {

    /** Multiplied onto each channel's own gain. */
    const val FACTOR = 1.6f
    /** The mixer's ceiling for one channel's gain with the boost on. */
    const val MAX_GAIN = 2.5f

    private val EARPHONE_TYPES = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        if (Build.VERSION.SDK_INT >= 26) add(AudioDeviceInfo.TYPE_USB_HEADSET)
    }

    fun earphonesConnected(audioManager: AudioManager): Boolean {
        if (Build.VERSION.SDK_INT < 23) {
            @Suppress("DEPRECATION")
            return audioManager.isWiredHeadsetOn
        }
        return try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isEarphoneType(it.type) }
        } catch (e: Exception) {
            false
        }
    }

    fun isEarphoneType(type: Int): Boolean = type in EARPHONE_TYPES

    fun apply(gain: Float, enabled: Boolean, audioManager: AudioManager): Float =
        if (enabled && earphonesConnected(audioManager)) (gain * FACTOR).coerceAtMost(MAX_GAIN) else gain

    /** Calls [onChange] on the main thread whenever an output is plugged in or out. */
    fun watch(audioManager: AudioManager, onChange: () -> Unit): Any? {
        if (Build.VERSION.SDK_INT < 23) return null
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onChange()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onChange()
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        return callback
    }

    fun unwatch(audioManager: AudioManager, token: Any?) {
        if (Build.VERSION.SDK_INT >= 23 && token is AudioDeviceCallback) {
            try { audioManager.unregisterAudioDeviceCallback(token) } catch (_: Exception) {}
        }
    }
}
