package com.guardianshield.app.commands

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Remote sound activation.
 *
 * Sets device volume to maximum and plays the default alarm ringtone
 * for 30 seconds. Used for finding a lost device or getting
 * a child's attention remotely.
 */
object SoundPlayer {

    private const val TAG = "GS_SoundPlayer"
    private const val ALARM_DURATION_MS = 30_000L // 30 seconds
    private var currentPlayer: MediaPlayer? = null

    /**
     * Play alarm sound at maximum volume.
     * Automatically stops after 30 seconds.
     */
    fun play(context: Context) {
        // Stop any currently playing alarm
        stop()

        try {
            // Set alarm volume to maximum
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

            // Also maximize media and ring volumes for good measure
            audioManager.setStreamVolume(
                AudioManager.STREAM_RING,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_RING), 0
            )

            // Get default alarm ringtone
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            currentPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, alarmUri)
                isLooping = true
                prepare()
                start()
            }

            Log.i(TAG, "Alarm playing at max volume")

            // Auto-stop after duration
            Handler(Looper.getMainLooper()).postDelayed({
                stop()
            }, ALARM_DURATION_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to play alarm", e)
        }
    }

    /**
     * Stop the currently playing alarm.
     */
    fun stop() {
        currentPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping alarm", e)
            }
        }
        currentPlayer = null
    }
}
