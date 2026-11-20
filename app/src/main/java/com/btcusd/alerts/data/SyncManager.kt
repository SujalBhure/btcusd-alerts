package com.btcusd.alerts.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

object SyncManager {
    private const val TAG = "SyncManager"
    private const val PREFS_NAME = "btc_alerts_sync"
    private const val KEY_SYNC_CODE = "sync_code"
    private const val KEY_LOCAL_URL = "local_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_LAST_SYNC_MS = "last_sync_ms"
    private const val KEY_LAST_STATUS = "last_status"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val processedMsgIds = mutableSetOf<String>()

    fun getSyncCode(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_SYNC_CODE, "") ?: ""
    }

    fun setSyncCode(context: Context, code: String) {
        val clean = code.trim().lowercase()
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_SYNC_CODE, clean).apply()
    }

    fun getLocalUrl(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_LOCAL_URL, "") ?: ""
    }

    fun setLocalUrl(context: Context, url: String) {
        val clean = url.trim().removeSuffix("/")
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_LOCAL_URL, clean).apply()
    }

    fun getDeviceId(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = sp.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrEmpty()) {
            id = "android_" + UUID.randomUUID().toString().replace("-", "").take(12)
            sp.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getLastSyncMs(context: Context): Long {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getLong(KEY_LAST_SYNC_MS, 0L)
    }

    fun getLastStatus(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_LAST_STATUS, "Ready") ?: "Ready"
    }

    private fun setStatus(context: Context, status: String, syncSuccess: Boolean = false) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = sp.edit().putString(KEY_LAST_STATUS, status)
        if (syncSuccess) {
            editor.putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis())
        }
        editor.apply()
    }

    fun getTopic(context: Context): String {
        val code = getSyncCode(context)
        return code.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.lowercase()
    }

    /**
     * Executes a complete sync cycle: poll remote changes, merge them, then push current state.
     * Returns true if remote changes updated the database.
     */
    suspend fun syncNow(context: Context, db: AlertDb): Boolean = withContext(Dispatchers.IO) {
        val syncCode = getSyncCode(context)
        val localUrl = getLocalUrl(context)

        if (syncCode.isEmpty() && localUrl.isEmpty()) {
            setStatus(context, "No Sync Code set")
            return@withContext false
        }

        setStatus(context, "Syncing...")
        var updated = false

        try {
            // 1. Poll incoming changes
            val pollChanged = pollState(context, db)
            if (pollChanged) updated = true

            // 2. Push local state out
            pushState(context, db)

            setStatus(context, "Connected", syncSuccess = true)
        } catch (e: Exception) {
            Log.e(TAG, "Sync error: ${e.message}", e)
            setStatus(context, "Error: ${e.message?.take(30)}")
        }

        updated
    }

    suspend fun pushState(context: Context, db: AlertDb) = withContext(Dispatchers.IO) {
        val deviceId = getDeviceId(context)
        val topic = getTopic(context)
        val localUrl = getLocalUrl(context)

        val rawAlerts = db.dao().getAllList()
        val now = System.currentTimeMillis()

        // Ensure every alert has a unique syncId and valid updatedAtMs
        val alertsJson = JSONArray()
        for (a in rawAlerts) {
            var sId = a.syncId
            var uAt = a.updatedAtMs
            if (sId.isEmpty()) {
                sId = UUID.randomUUID().toString().replace("-", "")
                uAt = now
                db.dao().update(a.copy(syncId = sId, updatedAtMs = uAt))
            } else if (uAt <= 0L) {
                uAt = now
                db.dao().update(a.copy(updatedAtMs = uAt))
            }

            val obj = JSONObject().apply {
                put("syncId", sId)
                put("symbol", a.symbol)
                put("targetPrice", a.targetPrice)
                put("direction", a.direction)
                put("oneShot", a.oneShot)
                put("active", a.active)
                put("alertType", a.alertType)
                put("triggerCondition", a.triggerCondition)
                put("timeframe", a.timeframe)
                put("updatedAtMs", uAt)
            }
            alertsJson.put(obj)
        }

        val deletedList = db.dao().getDeletedSyncIds()
        val deletedJson = JSONArray()
        deletedList.forEach { deletedJson.put(it) }

        val payload = JSONObject().apply {
            put("sender_id", deviceId)
            put("sender_type", "android")
            put("timestamp", now)
            put("alerts", alertsJson)
            put("deleted_ids", deletedJson)
        }

        val bodyString = payload.toString()
        val reqBody = bodyString.toRequestBody(JSON_MEDIA)

        // A. Push to Cloud if topic exists
        if (topic.isNotEmpty()) {
            val cloudReq = Request.Builder()
                .url("https://ntfy.sh/$topic")
                .header("Title", "AlertsSync")
                .header("Priority", "2")
                .header("Tags", "arrows_counterclockwise")
                .header("User-Agent", "BTCAlertsAndroid/1.0")
                .post(reqBody)
                .build()

            runCatching {
                httpClient.newCall(cloudReq).execute().use { resp ->
                    if (!resp.isSuccessful) Log.w(TAG, "Cloud push response: ${resp.code}")
                }
            }
        }

        // B. Push to Local LAN PC if configured
        if (localUrl.isNotEmpty()) {
            val lanUrl = if (localUrl.endsWith("/api/sync")) localUrl else "$localUrl/api/sync"
            val lanReq = Request.Builder()
                .url(lanUrl)
                .post(bodyString.toRequestBody(JSON_MEDIA))
                .build()

            runCatching {
                httpClient.newCall(lanReq).execute().use { resp ->
                    if (!resp.isSuccessful) Log.w(TAG, "LAN push response: ${resp.code}")
                }
            }
        }
    }

    suspend fun pollState(context: Context, db: AlertDb): Boolean = withContext(Dispatchers.IO) {
        val deviceId = getDeviceId(context)
        val topic = getTopic(context)
        val localUrl = getLocalUrl(context)
        var anyChanged = false

        // 1. Check Local LAN PC first if available
        if (localUrl.isNotEmpty()) {
            val lanUrl = if (localUrl.endsWith("/api/sync")) localUrl else "$localUrl/api/sync"
            runCatching {
                val req = Request.Builder().url(lanUrl).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        if (body.isNotEmpty()) {
                            val json = JSONObject(body)
                            val sender = json.optString("sender_id", "")
                            if (sender != deviceId) {
                                if (mergePayload(db, json)) anyChanged = true
                            }
                        }
                    }
                }
            }
        }

        // 2. Poll Cloud Relay
        if (topic.isNotEmpty()) {
            runCatching {
                val cloudUrl = "https://ntfy.sh/$topic/json?poll=1&since=10m"
                val req = Request.Builder()
                    .url(cloudUrl)
                    .header("User-Agent", "BTCAlertsAndroid/1.0")
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val lines = body.split("\n")
                        for (line in lines) {
                            val trimmed = line.trim()
                            if (trimmed.isEmpty()) continue

                            val eventObj = runCatching { JSONObject(trimmed) }.getOrNull() ?: continue
                            val msgId = eventObj.optString("id", "")
                            if (msgId.isNotEmpty() && processedMsgIds.contains(msgId)) continue
                            if (msgId.isNotEmpty()) {
                                processedMsgIds.add(msgId)
                                if (processedMsgIds.size > 200) processedMsgIds.clear()
                            }

                            val msgStr = eventObj.optString("message", "")
                            if (msgStr.isEmpty()) continue

                            val payload = runCatching { JSONObject(msgStr) }.getOrNull() ?: continue
                            val sender = payload.optString("sender_id", "")
                            if (sender == deviceId) continue

                            if (mergePayload(db, payload)) {
                                anyChanged = true
                            }
                        }
                    }
                }
            }
        }

        anyChanged
    }

    private suspend fun mergePayload(db: AlertDb, payload: JSONObject): Boolean {
        var changed = false
        val now = System.currentTimeMillis()

        // 1. Process deleted alerts
        val deletedArr = payload.optJSONArray("deleted_ids")
        if (deletedArr != null) {
            for (i in 0 until deletedArr.length()) {
                val sId = deletedArr.optString(i, "")
                if (sId.isNotEmpty()) {
                    val existing = db.dao().getBySyncId(sId)
                    if (existing != null) {
                        db.dao().deleteBySyncId(sId)
                        db.dao().recordDeleted(sId, now)
                        changed = true
                    }
                }
            }
        }

        // 2. Process incoming alerts
        val alertsArr = payload.optJSONArray("alerts") ?: return changed
        val localDeleted = db.dao().getDeletedSyncIds().toSet()

        for (i in 0 until alertsArr.length()) {
            val aObj = alertsArr.optJSONObject(i) ?: continue
            val syncId = aObj.optString("syncId", "")
            if (syncId.isEmpty() || localDeleted.contains(syncId)) continue

            val symbol = aObj.optString("symbol", "BTCUSD")
            val targetPrice = aObj.optDouble("targetPrice", 0.0)
            val direction = aObj.optString("direction", "cross")
            val oneShot = aObj.optBoolean("oneShot", true)
            val active = aObj.optBoolean("active", true)
            val alertType = aObj.optString("alertType", "LEVEL")
            val triggerCondition = aObj.optString("triggerCondition", TriggerDetector.COND_CROSS)
            val timeframe = aObj.optString("timeframe", "15")
            val updatedAtMs = aObj.optLong("updatedAtMs", 0L)

            val existing = db.dao().getBySyncId(syncId)
            if (existing == null) {
                // Insert new alert
                val newAlert = Alert(
                    symbol = symbol,
                    targetPrice = targetPrice,
                    direction = direction,
                    oneShot = oneShot,
                    active = active,
                    alertType = alertType,
                    triggerCondition = triggerCondition,
                    timeframe = timeframe,
                    syncId = syncId,
                    updatedAtMs = updatedAtMs
                )
                db.dao().insert(newAlert)
                changed = true
            } else {
                // If remote is newer, update local alert
                if (updatedAtMs > existing.updatedAtMs) {
                    val updatedAlert = existing.copy(
                        symbol = symbol,
                        targetPrice = targetPrice,
                        direction = direction,
                        oneShot = oneShot,
                        active = active,
                        alertType = alertType,
                        triggerCondition = triggerCondition,
                        timeframe = timeframe,
                        updatedAtMs = updatedAtMs
                    )
                    db.dao().update(updatedAlert)
                    changed = true
                }
            }
        }

        return changed
    }
}
