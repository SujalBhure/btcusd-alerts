package com.btcusd.alerts.data

/** A tradeable market in the app. XAUUSD tracks spot gold via Delta PAXG (OANDA has no free keyless feed). */
data class Market(
    val id: String,       // alert symbol key: BTCUSD, ETHUSD, SOLUSD, XAUUSD, TSLA, SPCX, NVDA, GOOGL, AMZN
    val label: String,    // chip text
    val sub: String,      // venue line
    val decimals: Int,
    val kind: Kind
) {
    sealed interface Kind {
        data class BybitInverse(val symbol: String) : Kind
        data class Delta(val symbol: String) : Kind
    }
}

val MARKETS = listOf(
    Market("BTCUSD", "BTCUSD", "Bybit perp • last", 1, Market.Kind.BybitInverse("BTCUSD")),
    Market("ETHUSD", "ETHUSD", "Bybit perp • last", 2, Market.Kind.BybitInverse("ETHUSD")),
    Market("SOLUSD", "SOLUSD", "Bybit perp • last", 2, Market.Kind.BybitInverse("SOLUSD")),
    Market("XAUUSD", "XAUUSD", "Gold • via PAXG", 2, Market.Kind.Delta("PAXGUSD")),
    Market("TSLA", "TSLA", "Delta India • Tesla", 2, Market.Kind.Delta("TSLAXUSD")),
    Market("SPCX", "SPCX", "Delta India • SpaceX", 2, Market.Kind.Delta("SPCXXUSD")),
    Market("NVDA", "NVDA", "Delta India • Nvidia", 2, Market.Kind.Delta("NVDAXUSD")),
    Market("GOOGL", "GOOGL", "Delta India • Google", 2, Market.Kind.Delta("GOOGLXUSD")),
    Market("AMZN", "AMZN", "Delta India • Amazon", 2, Market.Kind.Delta("AMZNXUSD"))
)

fun marketOf(id: String): Market = MARKETS.firstOrNull { it.id == id } ?: MARKETS[0]

fun fmtPrice(m: Market, p: Double): String = "%,.${m.decimals}f".format(p)
