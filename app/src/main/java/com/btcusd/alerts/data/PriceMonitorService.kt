package com.btcusd.alerts.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.btcusd.alerts.MainActivity
import com.btcusd.alerts.alarm.AlarmActivity
import com.btcusd.alerts.alarm.SoundSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.WebSocket

/**
 * Sticky foreground service: holds Bybit WS for BTCUSD perp lastPrice,
 * fires on CROSSING (either direction), rings even with app closed/locked
 * via high-importance full-screen notification (background launches are
 * blocked by Android, a notification full-screen intent is the way through).
 */
class PriceMonitorService : LifecycleService() {

    private lateinit var db: AlertDb
    private var ws: WebSocket? = null
    private var watchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var lastPrice: Double = 0.0
    @Volatile private var prevPrice: Double = 0.0
    @Volatile private var lastTickMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        ensureAlertChannel(this)
        db = Room.databaseBuilder(this, AlertDb::class.java, "alerts.db")
            .addMigrations(MIGRATION_1_2).build()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BTCAlerts:monitor")
            .apply { runCatching { acquire() } }
        startForeground(1, persistentNotification())
        connectWs()
        startWatchdog()
    }

    private fun persistentNotification(): Notification {
        val ch = "monitor"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ch, "Price monitor", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, ch)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Watching BTCUSD Perp • Bybit lastPrice")
            .setContentText(if (lastPrice > 0) "$$lastPrice" else "connecting…")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun connectWs() {
        runCatching { ws?.cancel() }
        ws = BybitApi.subscribeLastPrice(
            onPrice = { p -> lifecycleScope.launch { onTick(p) } },
            onDown = { }
        )
    }

    /** If the socket goes silent (Doze/OEM kill/network), reconnect + REST tick. */
    private fun startWatchdog() {
        watchJob?.cancel()
        watchJob = lifecycleScope.launch {
            while (isActive) {
                delay(30_000)
                if (System.currentTimeMillis() - lastTickMs > 45_000) {
                    connectWs()
                    BybitApi.fetchTicker()?.let { onTick(it.lastPrice) }
                }
            }
        }
    }

    private suspend fun onTick(price: Double) {
        val prev = lastPrice
        prevPrice = if (prev > 0) prev else price
        lastPrice = price
        lastTickMs = System.currentTimeMillis()
        val now = lastTickMs
        for (a in db.dao().active()) {
            if (now < a.snoozeUntilMs) continue
            if (now - a.lastFiredMs < 60_000) continue
            val d1 = prevPrice - a.targetPrice
            val d2 = price - a.targetPrice
            val crossed = d1 != 0.0 && (d1 < 0 != d2 < 0 || d2 == 0.0)
            if (crossed) {
                db.dao().markFired(a.id, now)
                fireAlarm(a, price)
            }
        }
    }

    private fun fireAlarm(a: Alert, price: Double) {
        ensureAlertChannel(this)
        val target = a.targetPrice
        val full = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("alertId", a.id)
            putExtra("oneShot", a.oneShot)
            putExtra("target", target)
            putExtra("price", price)
            putExtra("ringtone", a.ringtoneUri)
        }
        val pi = PendingIntent.getActivity(
            this, target.hashCode(), full,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sound = a.ringtoneUri?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            ?: SoundSettings.getCustomUri(this)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("BTCUSD crossed $${"%,.1f".format(target)}")
            .setContentText("Now $${"%,.1f".format(price)} • Tap to open")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setSound(sound)
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .build()
        getSystemService(NotificationManager::class.java).notify(target.hashCode(), notif)
        // Works when app is in foreground; the full-screen notification covers background/lock.
        runCatching { startActivity(full) }
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { ws?.cancel() }
        watchJob?.cancel()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        const val ALERT_CHANNEL = "btc_alerts"
        fun ensureAlertChannel(ctx: android.content.Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(ALERT_CHANNEL, "Price alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        enableVibration(true)
                    }
                )
            }
        }
    }
}
