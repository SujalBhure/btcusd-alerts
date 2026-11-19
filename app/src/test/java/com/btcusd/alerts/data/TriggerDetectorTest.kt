package com.btcusd.alerts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerDetectorTest {

    @Test
    fun testBullishPinBarHammer() {
        val target = 95000.0
        // Candle dipping to 94,920 (retesting target 95,000) and rejecting up to close at 95,250
        val candle = BybitApi.Candle(
            startMs = 1700000000000L,
            open = 95200.0,
            high = 95280.0,
            low = 94920.0,
            close = 95250.0
        )

        val match = TriggerDetector.checkPinBar(candle, target)
        assertNotNull("Should detect bullish pin bar", match)
        assertTrue(match!!.isBullish)
        assertTrue(match.title.contains("Bullish Pin Bar"))
    }

    @Test
    fun testBearishPinBarShootingStar() {
        val target = 95000.0
        // Candle spiking to 95,180 (testing target 95,000) and rejecting down to close at 94,750
        val candle = BybitApi.Candle(
            startMs = 1700000000000L,
            open = 94800.0,
            high = 95180.0,
            low = 94720.0,
            close = 94750.0
        )

        val match = TriggerDetector.checkPinBar(candle, target)
        assertNotNull("Should detect bearish pin bar", match)
        assertTrue(!match!!.isBullish)
        assertTrue(match.title.contains("Bearish Pin Bar"))
    }

    @Test
    fun testBullishEngulfing() {
        val target = 95000.0
        val prev = BybitApi.Candle(
            startMs = 1700000000000L,
            open = 95100.0,
            high = 95120.0,
            low = 95000.0,
            close = 95020.0 // Bearish candle
        )
        val curr = BybitApi.Candle(
            startMs = 1700000900000L,
            open = 95000.0,
            high = 95220.0,
            low = 94980.0,
            close = 95180.0 // Bullish engulfing
        )

        val match = TriggerDetector.checkEngulfing(curr, prev, target)
        assertNotNull("Should detect bullish engulfing", match)
        assertTrue(match!!.isBullish)
        assertEquals("Bullish Engulfing", match.title)
    }

    @Test
    fun testLevelRetest() {
        val target = 95000.0
        val candle = BybitApi.Candle(
            startMs = 1700000000000L,
            open = 95100.0,
            high = 95200.0,
            low = 94990.0, // Retests 95k support
            close = 95150.0 // Bounces
        )

        val match = TriggerDetector.checkRetest(candle, target)
        assertNotNull("Should detect support retest", match)
        assertTrue(match!!.isBullish)
        assertTrue(match.title.contains("Retest"))
    }

    @Test
    fun testAnyTriggerMatchesPinBarOrEngulfing() {
        val target = 95000.0
        val hammer = BybitApi.Candle(
            startMs = 1700000000000L,
            open = 95200.0,
            high = 95280.0,
            low = 94920.0,
            close = 95250.0
        )
        val match = TriggerDetector.evaluate(TriggerDetector.COND_ANY_TRIGGER, hammer, null, target)
        assertNotNull("ANY_TRIGGER should match pin bar", match)
        assertTrue(match!!.title.contains("Pin Bar"))
    }

    @Test
    fun testNormalCandleDoesNotTrigger() {
        val target = 95000.0
        // Plain symmetrical candle away from level
        val candle = BybitApi.Candle(
            startMs = 1700000000000L,
            open = 96000.0,
            high = 96200.0,
            low = 95900.0,
            close = 96100.0
        )

        val match = TriggerDetector.evaluate(TriggerDetector.COND_PINBAR, candle, null, target)
        assertNull("Should not match pin bar when away from level", match)
    }
}

