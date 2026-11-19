package com.btcusd.alerts.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Technical analysis pattern & entry trigger detection on candles:
 * - Level Retest: Price tests the key level within zone and bounces/holds
 * - Pin Bar (Hammer / Shooting Star): Long rejection wick at the level (>= 55% of range)
 * - Engulfing Bar: Full body reversal engulfing previous candle at the level
 * - Any Trigger: Evaluates if either Pin Bar or Engulfing occurred at the level
 */
object TriggerDetector {

    const val COND_CROSS = "CROSS"
    const val COND_RETEST = "RETEST"
    const val COND_PINBAR = "PINBAR"
    const val COND_ENGULFING = "ENGULFING"
    const val COND_ANY_TRIGGER = "ANY_TRIGGER"

    data class TriggerMatch(
        val title: String,        // e.g. "Bullish Pin Bar", "Bearish Engulfing", "Level Retest"
        val description: String,  // e.g. "Rejection wick tested $95,000 on 15m"
        val isBullish: Boolean
    )

    /**
     * Checks if a candle's price range [low, high] reached/tested the target price
     * within a sensible tolerance (0.2% zone).
     */
    fun testsLevel(c: BybitApi.Candle, target: Double, tolerance: Double = 0.002): Boolean {
        if (target <= 0.0) return true
        val minTarget = target * (1.0 - tolerance)
        val maxTarget = target * (1.0 + tolerance)
        return c.high >= minTarget && c.low <= maxTarget
    }

    /**
     * Detects Pin Bar (Hammer or Shooting Star) at or testing the target level.
     * Rejection shadow must be >= 55% of total candle range with small body (<= 35%).
     */
    fun checkPinBar(c: BybitApi.Candle, target: Double): TriggerMatch? {
        val totalRange = c.high - c.low
        if (totalRange <= 0.0) return null
        if (!testsLevel(c, target)) return null

        val body = abs(c.close - c.open)
        val upperShadow = c.high - max(c.open, c.close)
        val lowerShadow = min(c.open, c.close) - c.low

        // Bullish Pin Bar (Hammer) - long lower wick rejecting lower prices
        if (lowerShadow >= 0.55 * totalRange && body <= 0.35 * totalRange && upperShadow <= 0.25 * totalRange) {
            // Lower wick tested/pierced the target level
            return TriggerMatch(
                title = "Bullish Pin Bar (Hammer)",
                description = "Long lower rejection wick (${(lowerShadow / totalRange * 100).toInt()}%) tested level",
                isBullish = true
            )
        }

        // Bearish Pin Bar (Shooting Star) - long upper wick rejecting higher prices
        if (upperShadow >= 0.55 * totalRange && body <= 0.35 * totalRange && lowerShadow <= 0.25 * totalRange) {
            // Upper wick tested/pierced the target level
            return TriggerMatch(
                title = "Bearish Pin Bar (Shooting Star)",
                description = "Long upper rejection wick (${(upperShadow / totalRange * 100).toInt()}%) tested level",
                isBullish = false
            )
        }

        return null
    }

    /**
     * Detects Bullish or Bearish Engulfing candle at the target level.
     */
    fun checkEngulfing(c: BybitApi.Candle, prev: BybitApi.Candle?, target: Double): TriggerMatch? {
        if (prev == null) return null
        if (!testsLevel(c, target)) return null

        val currBody = abs(c.close - c.open)
        val prevBody = abs(prev.close - prev.open)
        if (currBody <= 0.0 || currBody < prevBody * 0.95) return null

        val prevBearish = prev.close < prev.open
        val currBullish = c.close > c.open
        if (prevBearish && currBullish) {
            // Bullish Engulfing: green candle body engulfs previous red candle body
            if (c.close >= prev.open * 0.999 && c.open <= prev.close * 1.001) {
                return TriggerMatch(
                    title = "Bullish Engulfing",
                    description = "Bullish body fully engulfed prior red candle at level",
                    isBullish = true
                )
            }
        }

        val prevBullish = prev.close > prev.open
        val currBearish = c.close < c.open
        if (prevBullish && currBearish) {
            // Bearish Engulfing: red candle body engulfs previous green candle body
            if (c.close <= prev.open * 1.001 && c.open >= prev.close * 0.999) {
                return TriggerMatch(
                    title = "Bearish Engulfing",
                    description = "Bearish body fully engulfed prior green candle at level",
                    isBullish = false
                )
            }
        }

        return null
    }

    /**
     * Detects Level Retest:
     * Candle tested the target level and closed holding/reversing from it.
     */
    fun checkRetest(c: BybitApi.Candle, target: Double): TriggerMatch? {
        if (!testsLevel(c, target)) return null

        val totalRange = c.high - c.low
        if (totalRange <= 0.0) return null

        // Support Retest: low dipped to target and closed above
        if (c.low <= target * 1.0015 && c.close >= target * 0.9985) {
            val bounced = c.close > c.open || (c.close - c.low) > 0.4 * totalRange
            if (bounced) {
                return TriggerMatch(
                    title = "Support Retest & Bounce",
                    description = "Level tested as support; closed above with bounce",
                    isBullish = true
                )
            }
        }

        // Resistance Retest: high spiked to target and closed below
        if (c.high >= target * 0.9985 && c.close <= target * 1.0015) {
            val rejected = c.close < c.open || (c.high - c.close) > 0.4 * totalRange
            if (rejected) {
                return TriggerMatch(
                    title = "Resistance Retest & Rejection",
                    description = "Level tested as resistance; closed below with rejection",
                    isBullish = false
                )
            }
        }

        return TriggerMatch(
            title = "Level Retest",
            description = "Price retested target level zone",
            isBullish = c.close >= c.open
        )
    }

    /**
     * Evaluates whether candle(s) match the alert's trigger condition.
     */
    fun evaluate(
        condition: String,
        candle: BybitApi.Candle,
        prevCandle: BybitApi.Candle?,
        targetPrice: Double
    ): TriggerMatch? {
        return when (condition.uppercase()) {
            COND_PINBAR -> checkPinBar(candle, targetPrice)
            COND_ENGULFING -> checkEngulfing(candle, prevCandle, targetPrice)
            COND_RETEST -> checkRetest(candle, targetPrice)
            COND_ANY_TRIGGER -> {
                // Priority: Pin Bar > Engulfing > Retest
                checkPinBar(candle, targetPrice)
                    ?: checkEngulfing(candle, prevCandle, targetPrice)
                    ?: checkRetest(candle, targetPrice)
            }
            else -> null
        }
    }
}

