package com.btcusd.alerts.data

/** Unified quote for any market. absChange + chgPct drive the watchlist change line. */
object Feed {
    data class Quote(val last: Double, val absChange: Double, val chgPct: Double)

    suspend fun quote(m: Market): Quote? = when (val k = m.kind) {
        is Market.Kind.BybitInverse -> BybitApi.tickerFor(k.symbol)?.let {
            Quote(it.lastPrice, it.lastPrice - it.prev24h, it.price24hPcnt * 100)
        }
        is Market.Kind.Delta -> DeltaApi.ticker(k.symbol)?.let {
            Quote(it.last, it.last * it.chgPct / 100, it.chgPct)
        }
        is Market.Kind.Swissquote -> {
            val spot = SwissquoteApi.spot(k.pair) ?: return null
            // Swissquote quotes carry no day stats → 24h change proxied from Delta metal token.
            val pct = DeltaApi.ticker(k.proxyDeltaSymbol)?.chgPct ?: 0.0
            Quote(spot.mid, spot.mid * pct / 100, pct)
        }
    }

    suspend fun candles(m: Market, interval: String = "15", limit: Int = 96): List<BybitApi.Candle> =
        when (val k = m.kind) {
            is Market.Kind.BybitInverse -> BybitApi.klinesFor(k.symbol, interval, limit)
            is Market.Kind.Delta -> DeltaApi.klines(k.symbol, interval, limit)
            is Market.Kind.Swissquote -> DeltaApi.klines(k.proxyDeltaSymbol, interval, limit)
        }

    fun calculateVwap(candles: List<BybitApi.Candle>): Double {
        if (candles.isEmpty()) return 0.0
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val sodMs = cal.timeInMillis
        var sessionCandles = candles.filter { it.startMs >= sodMs }
        if (sessionCandles.size < 3) {
            sessionCandles = if (candles.size >= 24) candles.takeLast(24) else candles
        }
        var totalVp = 0.0
        var totalVol = 0.0
        var totalTp = 0.0
        for (c in sessionCandles) {
            val tp = (c.high + c.low + c.close) / 3.0
            totalTp += tp
            if (c.volume > 0.0) {
                totalVp += tp * c.volume
                totalVol += c.volume
            }
        }
        return if (totalVol > 0.0) totalVp / totalVol
        else if (sessionCandles.isNotEmpty()) totalTp / sessionCandles.size
        else candles.last().close
    }

    suspend fun vwap(m: Market): Double {
        val cdls = candles(m, interval = "15", limit = 96)
        return calculateVwap(cdls)
    }
}
