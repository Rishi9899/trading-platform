package com.tradingplatform.indicator;

import com.tradingplatform.domain.candle.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ExponentialMovingAverageTest {

    @Test
    void notReadyUntilSeeded() {
        ExponentialMovingAverage ema = new ExponentialMovingAverage(3);
        ema.update(candle("10"));
        ema.update(candle("20"));
        assertFalse(ema.isReady());
        ema.update(candle("30"));
        assertTrue(ema.isReady());
    }

    @Test
    void seedsWithSmaThenAppliesExponentialFormula() {
        // period=3 -> multiplier = 2/(3+1) = 0.5
        ExponentialMovingAverage ema = new ExponentialMovingAverage(3);
        ema.update(candle("10"));
        ema.update(candle("20"));
        ema.update(candle("30")); // seed = SMA(10,20,30) = 20
        assertEquals(0, new BigDecimal("20").compareTo(ema.value().orElseThrow()));

        ema.update(candle("40")); // ema = (40-20)*0.5 + 20 = 30
        assertEquals(0, new BigDecimal("30").compareTo(ema.value().orElseThrow()));

        ema.update(candle("50")); // ema = (50-30)*0.5 + 30 = 40
        assertEquals(0, new BigDecimal("40").compareTo(ema.value().orElseThrow()));
    }

    @Test
    void tracksRisingPricesUpwardMoreResponsivelyThanSma() {
        int period = 5;
        ExponentialMovingAverage ema = new ExponentialMovingAverage(period);
        SimpleMovingAverage sma = new SimpleMovingAverage(period);

        int[] prices = {100, 105, 110, 115, 120, 130, 140, 150};
        for (int price : prices) {
            Candle c = candle(String.valueOf(price));
            ema.update(c);
            sma.update(c);
        }

        // EMA weights recent (higher) prices more heavily than SMA -> EMA should read higher
        assertTrue(ema.value().orElseThrow().compareTo(sma.value().orElseThrow()) > 0,
                "EMA should react faster to the recent upward run than SMA");
    }

    private static Candle candle(String close) {
        Instant now = Instant.now();
        return new Candle("NIFTY", "1m", now, now.plusSeconds(60),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), 100L);
    }
}