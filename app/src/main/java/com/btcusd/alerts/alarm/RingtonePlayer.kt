package com.btcusd.alerts.alarm

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Loops STREAM_ALARM at max + vibration until stop(). Call-style: never miss a trade. */
object RingtonePlayer {
    private var player: MediaPlayer? = null
    private var fallbackRingtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    var isPlaying by mutableStateOf(false)
        private set
    var activeTag by mutableStateOf<String?>(null)
        private set

    /**
     * Plays alert sound:
     * 1. alertRingtone (per-alert specific)
     * 2. alertType sound (e.g. SL, TP, ENTRY, LEVEL)
     * 3. universal custom ringtone
     * 4. system alarm default
     * 5. system ringtone default
     */
    fun start(ctx: Context, alertRingtone: Uri? = null, alertType: String? = null) {
        stop(ctx)
        val am = ctx.getSystemService(AudioManager::class.java)
        runCatching {
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }

        val uri = SoundSettings.resolveEffectiveUri(ctx, alertRingtone?.toString(), alertType)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        isPlaying = true
        activeTag = alertType ?: "ALARM"

        var started = false
        if (uri != null) {
            runCatching {
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(ctx, uri)
                    isLooping = true
                    prepare()
                    start()
                }
                started = true
            }
        }

        if (!started && uri != null) {
            runCatching {
                fallbackRingtone = RingtoneManager.getRingtone(ctx, uri).apply {
                    if (Build.VERSION.SDK_INT >= 28) {
                        isLooping = true
                    }
                    play()
                }
                started = true
            }
        }

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 600, 300, 600)
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
        }
    }

    /**
     * Plays a brief sample/test of a specific sound URI or key.
     * If the requested key is already playing, calling this STOPS it immediately!
     */
    fun testPlay(ctx: Context, testUri: Uri? = null, tag: String = "") {
        if (isPlaying && activeTag == tag) {
            stop(ctx)
            return
        }

        stop(ctx)
        val am = ctx.getSystemService(AudioManager::class.java)
        runCatching {
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }

        val uri = testUri
            ?: SoundSettings.getSoundUri(ctx, SoundSettings.KEY_UNIVERSAL)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        isPlaying = true
        activeTag = tag

        var started = false
        if (uri != null) {
            runCatching {
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(ctx, uri)
                    isLooping = false
                    setOnCompletionListener {
                        stop(ctx)
                    }
                    setOnErrorListener { _, _, _ ->
                        stop(ctx)
                        true
                    }
                    prepare()
                    start()
                }
                started = true
            }
        }

        if (!started && uri != null) {
            runCatching {
                fallbackRingtone = RingtoneManager.getRingtone(ctx, uri).apply {
                    play()
                }
            }
        }
    }

    fun stop(ctx: Context? = null) {
        runCatching {
            player?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        }
        player = null

        runCatching {
            fallbackRingtone?.stop()
        }
        fallbackRingtone = null

        runCatching { vibrator?.cancel() }
        vibrator = null

        isPlaying = false
        activeTag = null

        // Also dismiss any firing alarm notifications if context is provided
        ctx?.let {
            runCatching {
                it.getSystemService(NotificationManager::class.java)?.cancelAll()
            }
        }
    }
}
