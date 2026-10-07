package com.btcusd.alerts.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** Delta Exchange India public market data (no key needed). */
object DeltaApi {
    private const val BASE = "https://api.india.delta.exchange"
    private val client = OkHttpClient()

    data class Quote(val last: Double, val chgPct: Double, val high24: Double, val low24: Double)

    suspend fun ticker(symbol: String): Quote? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$BASE/v2/tickers/$symbol").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val r = JSONObject(resp.body!!.string()).getJSONObject("result")
                val mark = r.optDouble("mark_price", Double.NaN)
                val close = r.optDouble("close", Double.NaN)
                Quote(
                    last = if (mark.isFinite()) mark else close,
                    chgPct = r.optDouble("mark_change_24h", 0.0),
                    high24 = r.optDouble("mark_high_24h", 0.0),
                    low24 = r.optDouble("mark_low_24h", 0.0)
                )
            }
        }.getOrNull()
    }

    suspend fun klines(symbol: String, resolution: String = "15", limit: Int = 96): List<BybitApi.Candle> =
        withContext(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis() / 1000
                // resolution is minutes; over-fetch then trim
                val span = 60L * resolution.toLong() * (limit + 10)
                val url = "$BASE/v2/chart/history?symbol=$symbol&resolution=$resolution&from=${now - span}&to=$now"
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val r = JSONObject(resp.body!!.string()).getJSONObject("result")
                    if (r.optString("s") != "ok") return@withContext emptyList()
                    val t = r.getJSONArray("t"); val o = r.getJSONArray("o")
                    val h = r.getJSONArray("h"); val l = r.getJSONArray("l"); val c = r.getJSONArray("c")
                    val n = minOf(t.length(), o.length(), h.length(), l.length(), c.length())
                    List(n) { i ->
                        BybitApi.Candle(
                            t.getLong(i) * 1000,
                            o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i)
                        )
                    }.takeLast(limit)
                }
            }.getOrDefault(emptyList())
        }
}
