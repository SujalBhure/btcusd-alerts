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
}
