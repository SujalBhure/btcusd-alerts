package com.btcusd.alerts.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.btcusd.alerts.data.BybitApi

/**
 * Real Bybit perp candles — green/red bodies, wicks, last-price dashed line.
 * No fake data, no decoration.
 */
@Composable
fun CandleChart(candles: List<BybitApi.Candle>, lastPrice: Double) {
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        if (candles.isEmpty()) return@Canvas
        val hi = (candles.maxOf { it.high }.coerceAtLeast(lastPrice))
        val lo = (candles.minOf { it.low }.coerceAtMost(lastPrice))
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun y(p: Double) = size.height - ((p - lo) / span * size.height).toFloat()
        val step = size.width / candles.size
        candles.forEachIndexed { i, c ->
            val x = i * step + step / 2
            val up = c.close >= c.open
            val col = if (up) Color(0xFF0ECB81) else Color(0xFFF6465D)
            drawLine(col, Offset(x, y(c.high)), Offset(x, y(c.low)), strokeWidth = 2f)
            val top = y(maxOf(c.open, c.close))
            val bot = y(minOf(c.open, c.close))
            drawRect(col, Offset(x - step * 0.28f, top), androidx.compose.ui.geometry.Size(step * 0.56f, (bot - top).coerceAtLeast(3f)))
        }
        // last-price line
        val ly = y(lastPrice)
        var x = 0f
        while (x < size.width) {
            drawLine(Color.White.copy(alpha = .55f), Offset(x, ly), Offset(x + 10, ly), strokeWidth = 2f)
            x += 18f
        }
    }
}
