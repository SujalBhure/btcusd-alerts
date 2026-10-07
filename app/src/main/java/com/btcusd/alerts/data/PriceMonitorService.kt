package com.btcusd.alerts.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.btcusd.alerts.MainActivity
import com.btcusd.alerts.R
import com.btcusd.alerts.alarm.AlarmActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.WebSocket

/**
 * Sticky foreground service: holds Bybit WS for BTCUSD perp lastPrice,
 * checks alerts on every tick, fires full-screen AlarmActivity on hit.
 */
class PriceMonitorService : LifecycleService() {

    private lateinit var db: AlertDb
    private var ws: WebSocket? = null
    private var pollJob: Job? = null
    @Volatile private var lastPrice: Double = 0.0

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AlertDb::class.java, "alerts.db").build()
        startForeground(1, persistentNotification())
        connectWs()
        startRestFallback()
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
        val alarmIntent = Intent(this, AlarmActivity::class.java).let {
            it.putExtra("test", true)
            PendingIntent.getActivity(this, 9, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, ch)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Watching BTCUSD Perp • Bybit lastPrice")
            .setContentText(if (lastPrice > 0) "$$lastPrice" else "connecting…")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun connectWs() {
        ws?.cancel()
        ws = BybitApi.subscribeLastPrice(
            onPrice = { p -> lifecycleScope.launch { onTick(p) } },
            onDown = { /* REST fallback keeps us alive; WS re-connects below */ }
        )
        // Re-subscribe every 55s to dodge silent drops (Bybit ping window).
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(55_000)
                runCatching { connectWs() }
                BybitApi.fetchTicker()?.let { onTick(it.lastPrice) }
            }
        }
    }

    private suspend fun onTick(price: Double) {
        lastPrice = price
        val now = System.currentTimeMillis()
        for (a in db.dao().active()) {
            if (now < a.snoozeUntilMs) continue
            val hit = if (a.direction == "above") price >= a.targetPrice else price <= a.targetPrice
            // 60s cooldown per alert to avoid double-fire on whipsaw.
            if (hit && now - a.lastFiredMs > 60_000) {
                db.dao().markFired(a.id, now)
                fireAlarm(a.targetPrice, a.direction, price)
            }
        }
    }

    private fun startRestFallback() {
        lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                if (lastPrice == 0.0) BybitApi.fetchTicker()?.let { onTick(it.lastPrice) }
            }
        }
    }

    private fun fireAlarm(target: Double, dir: String, price: Double) {
        val i = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("target", target)
            putExtra("direction", dir)
            putExtra("price", price)
        }
        startActivity(i)
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        ws?.cancel()
        pollJob?.cancel()
        super.onDestroy()
    }

    companion object {
        const val ALERT_CHANNEL = "btc_alerts"
        fun ensureAlertChannel(ctx: android.content.Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        ALERT_CHANNEL, "Price alerts",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        enableVibration(true)
                    }
                )
            }
        }
    }
}
