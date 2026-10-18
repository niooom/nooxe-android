package com.nooxe.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Rings and vibrates while a call is coming in, even with the app closed. */
object Ringer {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    fun start(context: Context) {
        stop()
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (uri != null) {
                val media = MediaPlayer()
                media.setDataSource(context, uri)
                media.isLooping = true
                media.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                media.prepare()
                media.start()
                player = media
            }
        }
        startVibration(context)
    }

    fun stop() {
        val media = player
        player = null
        if (media != null) {
            runCatching { media.stop() }
            runCatching { media.release() }
        }
        val device = vibrator
        vibrator = null
        if (device != null) {
            runCatching { device.cancel() }
        }
    }

    private fun startVibration(context: Context) {
        runCatching {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (device == null) return@runCatching
            val pattern = longArrayOf(0L, 900L, 900L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                device.vibrate(pattern, 0)
            }
            vibrator = device
        }
    }
}
