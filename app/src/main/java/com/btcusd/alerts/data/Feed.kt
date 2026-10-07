package com.btcusd.alerts.data

/** Unified quote + candles for any market (Bybit inverse or Delta India). */
object Feed {
    data class Quote(val last: Double, val chgPct: Double, val high24: Double, val low24: Double)

    suspend fun quote(m: Market): Quote? = when (val k = m.kind) {
        is Market.Kind.BybitInverse -> BybitApi.tickerFor(k.symbol)?.let {
            Quote(it.lastPrice, it.price24hPcnt * 100, it.high24h, it.low24h)
        }
        is Market.Kind.Delta -> DeltaApi.ticker(k.symbol)?.let {
            Quote(it.last, it.chgPct, it.high24, it.low24)
        }
    }

    suspend fun candles(m: Market, interval: String = "15", limit: Int = 96): List<BybitApi.Candle> =
        when (val k = m.kind) {
            is Market.Kind.BybitInverse -> BybitApi.klinesFor(k.symbol, interval, limit)
            is Market.Kind.Delta -> DeltaApi.klines(k.symbol, interval, limit)
        }
}
