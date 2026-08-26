package com.tradingplatform.indicator;

import com.tradingplatform.domain.candle.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AverageTrueRangeTest {

    @Test
    void notReadyUntilPeriodTrueRangesAccumulated() {
        AverageTrueRange atr = new AverageTrueRange(3);
        atr.update(ohlc("100", "105", "95", "100")); // establishes previousClose only
        atr.update(ohlc("100", "106", "94", "101"));
        atr.update(ohlc("101", "107", "95", "102"));
        assertFalse(atr.isReady());
        atr.update(ohlc("102", "108", "96", "103"));
        assertTrue(atr.isReady());
    }

    @Test
    void trueRangeAccountsForGapsBeyondCurrentHighLow() {
        // A gap-up open where the entire candle sits above the previous close
        // means true range should be measured from the previous close, not
        // just this candle's own high-low.
        AverageTrueRange atr = new AverageTrueRange(1);
        atr.update(ohlc("100", "100", "100", "100")); // previousClose = 100
        atr.update(ohlc("110", "111", "109", "110")); // high-low=2, but gap from 100 -> |111-100|=11
        assertTrue(atr.isReady());
        assertEquals(0, new BigDecimal("11").compareTo(atr.value().orElseThrow()));
    }

    @Test
    void higherVolatilityProducesHigherAtr() {
        AverageTrueRange calm = new AverageTrueRange(2);
        calm.update(ohlc("100", "101", "99", "100"));
        calm.update(ohlc("100", "101", "99", "100"));
        calm.update(ohlc("100", "101", "99", "100"));

        AverageTrueRange volatile_ = new AverageTrueRange(2);
        volatile_.update(ohlc("100", "110", "90", "100"));
        volatile_.update(ohlc("100", "110", "90", "100"));
        volatile_.update(ohlc("100", "110", "90", "100"));

        assertTrue(volatile_.value().orElseThrow().compareTo(calm.value().orElseThrow()) > 0);
    }

    private static Candle ohlc(String open, String high, String low, String close) {
        Instant now = Instant.now();
        return new Candle("NIFTY", "1m", now, now.plusSeconds(60),
                new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), 100L);
    }
}
