package com.tradingplatform.indicator;

import com.tradingplatform.domain.candle.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RelativeStrengthIndexTest {

    @Test
    void notReadyUntilPeriodPriceChangesObserved() {
        RelativeStrengthIndex rsi = new RelativeStrengthIndex(3);
        rsi.update(candle("100")); // just establishes starting close, no change yet
        assertFalse(rsi.isReady());
        rsi.update(candle("101")); // change 1 of 3
        assertFalse(rsi.isReady());
        rsi.update(candle("102")); // change 2 of 3
        assertFalse(rsi.isReady());
        rsi.update(candle("103")); // change 3 of 3 -> ready
        assertTrue(rsi.isReady());
    }

    @Test
    void allGainsPushesRsiToward100() {
        RelativeStrengthIndex rsi = new RelativeStrengthIndex(5);
        int price = 100;
        for (int i = 0; i < 20; i++) {
            price += 2;
            rsi.update(candle(String.valueOf(price)));
        }
        BigDecimal value = rsi.value().orElseThrow();
        assertTrue(value.compareTo(new BigDecimal("90")) > 0,
                "sustained gains with zero losses should push RSI close to 100, got " + value);
    }

    @Test
    void allLossesPushesRsiToward0() {
        RelativeStrengthIndex rsi = new RelativeStrengthIndex(5);
        int price = 200;
        for (int i = 0; i < 20; i++) {
            price -= 2;
            rsi.update(candle(String.valueOf(price)));
        }
        BigDecimal value = rsi.value().orElseThrow();
        assertTrue(value.compareTo(new BigDecimal("10")) < 0,
                "sustained losses with zero gains should push RSI close to 0, got " + value);
    }

    @Test
    void valueStaysWithinValidZeroToHundredRange() {
        RelativeStrengthIndex rsi = new RelativeStrengthIndex(5);
        int[] prices = {100, 102, 99, 105, 101, 98, 110, 108, 95, 120};
        for (int price : prices) {
            rsi.update(candle(String.valueOf(price)));
            rsi.value().ifPresent(v -> {
                assertTrue(v.compareTo(BigDecimal.ZERO) >= 0 && v.compareTo(new BigDecimal("100")) <= 0,
                        "RSI must stay within [0,100], got " + v);
            });
        }
    }

    @Test
    void retainsFullPrecisionRatherThanRoundingToFourDecimalPlaces() {
        // period=3, crafted so avgGain/avgLoss = (2/3)/(1/3) = 2 exactly,
        // but the intermediate averages themselves (2/3, 1/3) are
        // repeating decimals - representable with full MathContext
        // precision but NOT exactly as a 4-decimal-place rounded value.
        RelativeStrengthIndex rsi = new RelativeStrengthIndex(3);
        rsi.update(candle("100")); // establishes starting close
        rsi.update(candle("101")); // change +1 (gain)
        rsi.update(candle("100")); // change -1 (loss)
        rsi.update(candle("101")); // change +1 (gain) -> 3rd change, ready here

        BigDecimal value = rsi.value().orElseThrow();

        assertTrue(value.scale() > 4,
                "RSI must retain full internal precision, not be rounded to 4 decimal places. "
                        + "Got scale=" + value.scale() + ", value=" + value);
    }

    private static Candle candle(String close) {
        Instant now = Instant.now();
        return new Candle("NIFTY", "1m", now, now.plusSeconds(60),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), 100L);
    }
}