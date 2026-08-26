package com.tradingplatform.indicator;

import com.tradingplatform.domain.candle.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class BollingerBandsTest {

    @Test
    void notReadyUntilPeriodCandlesSeen() {
        BollingerBands bands = new BollingerBands(3, BigDecimal.valueOf(2.0));
        bands.update(candle("10"));
        bands.update(candle("10"));
        assertFalse(bands.isReady());
        bands.update(candle("10"));
        assertTrue(bands.isReady());
    }

    @Test
    void constantPricesProduceZeroWidthBandsCenteredOnPrice() {
        BollingerBands bands = new BollingerBands(3, BigDecimal.valueOf(2.0));
        bands.update(candle("50"));
        bands.update(candle("50"));
        bands.update(candle("50"));

        var value = bands.value().orElseThrow();
        assertEquals(0, new BigDecimal("50").compareTo(value.middle()));
        assertEquals(0, value.upper().compareTo(value.lower()));
    }

    @Test
    void widerPriceDispersionProducesWiderBands() {
        BollingerBands tightBands = new BollingerBands(4, BigDecimal.valueOf(2.0));
        for (String price : new String[]{"100", "101", "99", "100"}) {
            tightBands.update(candle(price));
        }

        BollingerBands wideBands = new BollingerBands(4, BigDecimal.valueOf(2.0));
        for (String price : new String[]{"100", "130", "70", "100"}) {
            wideBands.update(candle(price));
        }

        var tight = tightBands.value().orElseThrow();
        var wide = wideBands.value().orElseThrow();
        BigDecimal tightWidth = tight.upper().subtract(tight.lower());
        BigDecimal wideWidth = wide.upper().subtract(wide.lower());
        assertTrue(wideWidth.compareTo(tightWidth) > 0);
    }

    @Test
    void rejectsPeriodLessThanTwo() {
        assertThrows(IllegalArgumentException.class, () -> new BollingerBands(1, BigDecimal.valueOf(2.0)));
    }

    private static Candle candle(String close) {
        Instant now = Instant.now();
        return new Candle("NIFTY", "1m", now, now.plusSeconds(60),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), 100L);
    }
}
