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
 * Sticky foreground service: watches every market that has an active alert.
 * Bybit inverse markets share one WebSocket; Delta markets are REST-polled.
 * Fires on CROSSING (either direction), rings via full-screen notification
 * even with app closed / screen locked.
 */
class PriceMonitorService : LifecycleService() {

    private lateinit var db: AlertDb
    private var ws: WebSocket? = null
    private var watchJob: Job? = null
    private var pollJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val prev = mutableMapOf<String, Double>()
    private val lastTick = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        ensureAlertChannel(this)
        db = Room.databaseBuilder(this, AlertDb::class.java, "alerts.db")
            .addMigrations(MIGRATION_1_2).build()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BTCAlerts:monitor")
            .apply { runCatching { acquire() } }
        startForeground(1, persistentNotification())
        lifecycleScope.launch { refreshFeeds() }
        startWatchdog()
        startPollLoop()
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
            .setContentTitle("Watching alerts • Bybit + Delta")
            .setContentText("tap to open BTC Alerts")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    /** Bybit symbols currently needing live ticks (have active alerts, default BTCUSD). */
    private suspend fun bybitSyms(): Set<String> {
        val ids = db.dao().active().map { it.symbol }.ifEmpty { listOf("BTCUSD") }
        return ids.mapNotNull { id ->
            (marketOf(id).kind as? Market.Kind.BybitInverse)?.symbol
        }.toSet()
    }

    private suspend fun restMarkets(): List<Market> {
        val ids = db.dao().active().map { it.symbol }.ifEmpty { listOf("BTCUSD") }
        return ids.map { marketOf(it) }.filter { it.kind !is Market.Kind.BybitInverse }.distinctBy { it.id }
    }

    private suspend fun refreshFeeds() {
        connectWs()
        pollAll()
    }

    private fun connectWs() {
        lifecycleScope.launch {
            runCatching { ws?.cancel() }
            val syms = bybitSyms().ifEmpty { setOf("BTCUSD") }
            ws = BybitApi.subscribePrices(
                syms,
                onPrice = { s, p -> lifecycleScope.launch { onTick(s, p) } },
                onDown = { }
            )
        }
    }

    /** If the socket goes silent (Doze/OEM kill/network), reconnect + REST tick. */
    private fun startWatchdog() {
        watchJob?.cancel()
        watchJob = lifecycleScope.launch {
            while (isActive) {
                delay(30_000)
                val stale = bybitSyms().any { (lastTick[it] ?: 0) < System.currentTimeMillis() - 45_000 }
                if (stale) {
                    connectWs()
                    pollAll()
                }
            }
        }
    }

    /** Non-Bybit markets (Delta, Swissquote) are REST-polled every 20s. */
    private fun startPollLoop() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(20_000)
                for (m in restMarkets()) {
                    Feed.quote(m)?.let { onTick(m.id, it.last) }
                }
            }
        }
    }

    private suspend fun pollAll() {
        for (s in bybitSyms()) BybitApi.tickerFor(s)?.let { onTick(s, it.lastPrice) }
        for (m in restMarkets()) {
            Feed.quote(m)?.let { onTick(m.id, it.last) }
        }
    }

    private suspend fun onTick(marketId: String, price: Double) {
        val p = prev[marketId]
        prev[marketId] = price
        lastTick[marketId] = System.currentTimeMillis()
        if (p == null || p <= 0) return
        val now = System.currentTimeMillis()
        for (a in db.dao().active().filter { it.symbol == marketId }) {
            if (now < a.snoozeUntilMs) continue
            if (now - a.lastFiredMs < 60_000) continue
            val d1 = p - a.targetPrice
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
        val m = marketOf(a.symbol)
        val full = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("alertId", a.id)
            putExtra("oneShot", a.oneShot)
            putExtra("symbol", m.id)
            putExtra("target", target)
            putExtra("price", price)
            putExtra("ringtone", a.ringtoneUri)
        }
        val pi = PendingIntent.getActivity(
            this, a.id.toInt(), full,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sound = a.ringtoneUri?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            ?: SoundSettings.getCustomUri(this)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("${m.id} crossed $${fmtPrice(m, target)}")
            .setContentText("Now $${fmtPrice(m, price)} • Tap to open")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setSound(sound)
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .build()
        getSystemService(NotificationManager::class.java).notify(a.id.toInt(), notif)
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
        pollJob?.cancel()
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
