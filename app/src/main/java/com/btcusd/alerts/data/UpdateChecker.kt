package com.btcusd.alerts.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.btcusd.alerts.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** Checks github.com/SujalBhure/btcusd-alerts releases for a newer tag, downloads + prompts install. */
object UpdateChecker {
    private const val LATEST_URL =
        "https://api.github.com/SujalBhure/btcusd-alerts/releases/latest"
    private const val PREFS = "btc_settings"
    private const val KEY_SNOOZE = "update_snooze_until"
    private val client = OkHttpClient()

    data class Update(val tag: String, val apkUrl: String)

    private fun norm(v: String) = v.trim().removePrefix("v")

    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(LATEST_URL).header("Accept", "application/vnd.github+json").build()
            OkHttpClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val obj = JSONObject(resp.body!!.string())
                val tag = obj.getString("tag_name")
                if (norm(tag) == norm(com.btcusd.alerts.BuildConfig.VERSION_NAME)) return@withContext null
                val assets = obj.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val url = a.getString("browser_download_url")
                    if (url.endsWith(".apk")) return@withContext Update(tag, url)
                }
                null
            }
        }.getOrNull()
    }

    fun snoozed(ctx: Context): Boolean =
        System.currentTimeMillis() < ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SNOOZE, 0)

    fun snooze24h(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SNOOZE, System.currentTimeMillis() + 24 * 60 * 60_000).apply()
    }

    /** Enqueues APK download; on completion fires the system install prompt. */
    fun downloadAndInstall(ctx: Context, url: String) {
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "btc-alerts-update.apk")
        if (file.exists()) file.delete()
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(url))
                .setTitle("BTC Alerts update")
                .setDestinationUri(Uri.fromFile(file))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        )
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                runCatching { ctx.unregisterReceiver(this) }
                val uri = FileProvider.getUriForFile(ctx, "com.btcusd.alerts.fileprovider", file)
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }
}
