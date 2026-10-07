package com.btcusd.alerts.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** Bybit BTCUSD inverse perpetual — lastPrice only. No USDT. */
object BybitApi {
    const val SYMBOL = "BTCUSD"
    const val CATEGORY = "inverse"
    const val WS_URL = "wss://stream.bybit.com/v5/public/inverse"
    const val REST_TICKER =
        "https://api.bybit.com/v5/market/tickers?category=inverse&symbol=BTCUSD"
    const val REST_KLINE =
        "https://api.bybit.com/v5/market/kline?category=inverse&symbol=BTCUSD"

    private val client = OkHttpClient()

    data class Ticker(val lastPrice: Double, val price24hPcnt: Double, val high24h: Double, val low24h: Double)
    data class Candle(val startMs: Long, val open: Double, val high: Double, val low: Double, val close: Double)

    suspend fun fetchTicker(): Ticker? = tickerFor("BTCUSD")

    suspend fun tickerFor(symbol: String): Ticker? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("https://api.bybit.com/v5/market/tickers?category=inverse&symbol=$symbol").build()
            client.newCall(req).execute().use { resp ->
                val obj = JSONObject(resp.body!!.string())
                val item = obj.getJSONObject("result").getJSONArray("list").getJSONObject(0)
                Ticker(
                    lastPrice = item.getString("lastPrice").toDouble(),
                    price24hPcnt = item.optString("price24hPcnt", "0").toDouble(),
                    high24h = item.optString("highPrice24h", "0").toDoubleOrNull() ?: 0.0,
                    low24h = item.optString("lowPrice24h", "0").toDoubleOrNull() ?: 0.0
                )
            }
        }.getOrNull()
    }

    suspend fun fetchKlines(interval: String = "15", limit: Int = 120): List<Candle> =
        klinesFor("BTCUSD", interval, limit)

    suspend fun klinesFor(symbol: String, interval: String = "15", limit: Int = 120): List<Candle> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("https://api.bybit.com/v5/market/kline?category=inverse&symbol=$symbol&interval=$interval&limit=$limit").build()
                client.newCall(req).execute().use { resp ->
                    val obj = JSONObject(resp.body!!.string())
                    val arr = obj.getJSONObject("result").getJSONArray("list")
                    List(arr.length()) { i ->
                        val c = arr.getJSONArray(i)
                        Candle(c.getString(0).toLong(), c.getString(1).toDouble(), c.getString(2).toDouble(), c.getString(3).toDouble(), c.getString(4).toDouble())
                    }.reversed()
                }
            }.getOrDefault(emptyList())
        }

    /** Live lastPrice stream. Calls onPrice on every tickers.BTCUSD update. */
    fun subscribeLastPrice(onPrice: (Double) -> Unit, onDown: () -> Unit = {}): WebSocket =
        subscribePrices(setOf("BTCUSD"), { _, p -> onPrice(p) }, onDown)

    /** One socket, many Bybit inverse symbols. Routes ticks as (symbol, price). */
    fun subscribePrices(
        symbols: Set<String>,
        onPrice: (String, Double) -> Unit,
        onDown: () -> Unit = {}
    ): WebSocket {
        val req = Request.Builder().url(WS_URL).build()
        return client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val args = symbols.joinToString(",") { "\"tickers.$it\"" }
                ws.send("""{"op":"subscribe","args":[$args]}""")
            }
            override fun onMessage(ws: WebSocket, text: String) {
                runCatching {
                    val obj = JSONObject(text)
                    val topic = obj.optString("topic")
                    if (topic.startsWith("tickers.")) {
                        val d = obj.getJSONObject("data")
                        onPrice(topic.removePrefix("tickers."), d.getString("lastPrice").toDouble())
                    }
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) { onDown() }
        })
    }
}
