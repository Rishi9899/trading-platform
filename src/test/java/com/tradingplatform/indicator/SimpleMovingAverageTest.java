package com.tradingplatform.indicator;

import com.tradingplatform.domain.candle.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SimpleMovingAverageTest {

    @Test
    void notReadyUntilPeriodCandlesSeen() {
        SimpleMovingAverage sma = new SimpleMovingAverage(3);
        sma.update(candle("10"));
        assertFalse(sma.isReady());
        sma.update(candle("20"));
        assertFalse(sma.isReady());
        sma.update(candle("30"));
        assertTrue(sma.isReady());
    }

    @Test
    void computesCorrectSlidingAverage() {
        SimpleMovingAverage sma = new SimpleMovingAverage(3);
        sma.update(candle("10"));
        sma.update(candle("20"));
        sma.update(candle("30"));
        assertEquals(0, new BigDecimal("20").compareTo(sma.value().orElseThrow()));

        sma.update(candle("40")); // window is now [20,30,40]
        assertEquals(0, new BigDecimal("30").compareTo(sma.value().orElseThrow()));

        sma.update(candle("50")); // window is now [30,40,50]
        assertEquals(0, new BigDecimal("40").compareTo(sma.value().orElseThrow()));
    }

    private static Candle candle(String close) {
        Instant now = Instant.now();
        return new Candle("NIFTY", "1m", now, now.plusSeconds(60),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), 100L);
    }
}