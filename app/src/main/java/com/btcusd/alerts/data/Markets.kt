package com.btcusd.alerts.data

/** A tradeable market. XAU/XAG are Swissquote FX spot (keyless, real-time). */
data class Market(
    val id: String,       // alert symbol key
    val label: String,    // watchlist symbol text
    val desc: String,     // watchlist subtitle
    val decimals: Int,
    val badge: String,    // monogram in the icon circle
    val badgeBg: Long,    // icon circle color
    val badgeFg: Long,    // monogram color
    val kind: Kind
) {
    sealed interface Kind {
        data class BybitInverse(val symbol: String) : Kind
        data class Delta(val symbol: String) : Kind
        data class Swissquote(val pair: String, val proxyDeltaSymbol: String) : Kind
    }
}

val MARKETS = listOf(
    Market("BTCUSD", "BTCUSD.P", "BTCUSD Perpetual Contract", 1, "B", 0xFFF7931A, 0xFF000000, Market.Kind.BybitInverse("BTCUSD")),
    Market("ETHUSD", "ETHUSD.P", "ETHUSD Perpetual Contract", 2, "E", 0xFF627EEA, 0xFFFFFFFF, Market.Kind.BybitInverse("ETHUSD")),
    Market("SOLUSD", "SOLUSD.P", "SOLUSD Perpetual Contract", 2, "S", 0xFF9945FF, 0xFFFFFFFF, Market.Kind.BybitInverse("SOLUSD")),
    Market("XAUUSD", "XAUUSD", "Gold Spot / U.S. Dollar", 2, "Au", 0xFFFFC93C, 0xFF000000, Market.Kind.Swissquote("XAU/USD", "PAXGUSD")),
    Market("XAGUSD", "XAGUSD", "Silver / U.S. Dollar", 3, "Ag", 0xFFC0C0C0, 0xFF000000, Market.Kind.Swissquote("XAG/USD", "SLVONUSD")),
    Market("TSLA", "TSLA", "Delta India • Tesla", 2, "T", 0xFFE31937, 0xFFFFFFFF, Market.Kind.Delta("TSLAXUSD")),
    Market("SPCX", "SPCX", "Delta India • SpaceX", 2, "X", 0xFFF2F2F0, 0xFF000000, Market.Kind.Delta("SPCXXUSD")),
    Market("NVDA", "NVDA", "Delta India • Nvidia", 2, "N", 0xFF76B900, 0xFF000000, Market.Kind.Delta("NVDAXUSD")),
    Market("GOOGL", "GOOGL", "Delta India • Google", 2, "G", 0xFF4285F4, 0xFFFFFFFF, Market.Kind.Delta("GOOGLXUSD")),
    Market("AMZN", "AMZN", "Delta India • Amazon", 2, "A", 0xFFFF9900, 0xFF000000, Market.Kind.Delta("AMZNXUSD"))
)

fun marketOf(id: String): Market = MARKETS.firstOrNull { it.id == id } ?: MARKETS[0]

fun fmtPrice(m: Market, p: Double): String = "%,.${m.decimals}f".format(p)
