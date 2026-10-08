package com.btcusd.alerts.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/** Swissquote public quotes — real FX spot, no key. Mid price drives XAU/XAG alerts. */
object SwissquoteApi {
    private val client = OkHttpClient()

    data class Spot(val bid: Double, val ask: Double) {
        val mid: Double get() = (bid + ask) / 2
    }

    suspend fun spot(pair: String): Spot? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/$pair")
                .header("User-Agent", "BTC-Alerts-App").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val arr = JSONArray(resp.body!!.string())
                if (arr.length() == 0) return@withContext null
                val profiles = arr.getJSONObject(0).getJSONArray("spreadProfilePrices")
                if (profiles.length() == 0) return@withContext null
                val p = profiles.getJSONObject(0)
                Spot(p.getDouble("bid"), p.getDouble("ask"))
            }
        }.getOrNull()
    }
}
