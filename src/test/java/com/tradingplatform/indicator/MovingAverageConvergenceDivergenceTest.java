package com.tradingplatform.indicator;

import com.tradingplatform.domain.candle.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MovingAverageConvergenceDivergenceTest {

    @Test
    void notReadyUntilSlowEmaAndSignalEmaBothSeeded() {
        // fast=2, slow=3, signal=2 -> slow EMA seeds at candle 3 (first MACD value feeds
        // signal EMA), a second MACD value at candle 4 is what seeds the signal EMA itself.
        MovingAverageConvergenceDivergence macd = new MovingAverageConvergenceDivergence(2, 3, 2);
        int[] prices = {10, 11, 12};
        for (int price : prices) {
            macd.update(candle(String.valueOf(price)));
        }
        assertFalse(macd.isReady());

        macd.update(candle("13"));
        assertTrue(macd.isReady());
    }

    @Test
    void rejectsFastPeriodNotLessThanSlowPeriod() {
        assertThrows(IllegalArgumentException.class,
                () -> new MovingAverageConvergenceDivergence(26, 12, 9));
    }

    @Test
    void histogramIsMacdLineMinusSignalLine() {
        MovingAverageConvergenceDivergence macd = new MovingAverageConvergenceDivergence(2, 3, 2);
        int[] prices = {10, 11, 12, 13, 14, 16, 18, 20};
        for (int price : prices) {
            macd.update(candle(String.valueOf(price)));
        }
        assertTrue(macd.isReady());

        var value = macd.value().orElseThrow();
        BigDecimal expectedHistogram = value.macdLine().subtract(value.signalLine());
        assertEquals(0, expectedHistogram.compareTo(value.histogram()));
    }

    @Test
    void steadyUptrendProducesPositiveMacdLine() {
        MovingAverageConvergenceDivergence macd = new MovingAverageConvergenceDivergence(3, 6, 3);
        int[] prices = {100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 120};
        for (int price : prices) {
            macd.update(candle(String.valueOf(price)));
        }
        assertTrue(macd.isReady());
        // Fast EMA reacts faster to the uptrend than slow EMA -> fast > slow -> positive MACD line
        assertTrue(macd.value().orElseThrow().macdLine().compareTo(BigDecimal.ZERO) > 0);
    }

    private static Candle candle(String close) {
        Instant now = Instant.now();
        return new Candle("NIFTY", "1m", now, now.plusSeconds(60),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), 100L);
    }
}
