package com.btcusd.alerts.data

/**
 * The 3 Core Tradeable Markets:
 * 1. BTCUSD (Bybit Inverse Perpetual) -> BYBIT:BTCUSD
 * 2. XAUUSD (Gold Spot / U.S. Dollar) -> OANDA:XAUUSD
 * 3. USOIL  (CFDs on WTI Crude Oil)   -> TVC:USOIL
 */
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
    Market("XAUUSD", "XAUUSD", "Gold Spot / U.S. Dollar", 2, "Au", 0xFFFFC93C, 0xFF000000, Market.Kind.Swissquote("XAU/USD", "PAXGUSD")),
    Market("USOIL", "USOIL", "CFDs on WTI Crude Oil", 2, "Oil", 0xFF1E293B, 0xFFFFA726, Market.Kind.Swissquote("OIL/USD", "OILUSD"))
)

fun marketOf(id: String): Market = MARKETS.firstOrNull { it.id == id } ?: MARKETS[0]

fun fmtPrice(m: Market, p: Double): String = "%,.${m.decimals}f".format(p)

fun tradingViewSymbol(market: Market): String = when (market.id) {
    "BTCUSD" -> "BYBIT:BTCUSD"
    "XAUUSD" -> "OANDA:XAUUSD"
    "USOIL" -> "TVC:USOIL"
    else -> market.id
}

fun tradingViewUrl(market: Market): String {
    val sym = java.net.URLEncoder.encode(tradingViewSymbol(market), "UTF-8")
    return "https://www.tradingview.com/chart/?symbol=$sym"
}
