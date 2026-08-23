package com.tradingplatform.candle;

import com.tradingplatform.domain.candle.Candle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandleAggregatorTest {

    private static final Instant WINDOW_START = Instant.parse("2024-01-01T09:15:00Z");

    private CandleAggregator aggregator;
    private List<Candle> emitted;

    @BeforeEach
    void setUp() {
        aggregator = new CandleAggregator(Duration.ofSeconds(60), Duration.ofSeconds(300), "5m");
        emitted = new ArrayList<>();
        aggregator.addListener(emitted::add);
    }

    @Test
    void aggregatesFiveOneMinuteCandlesIntoOneFiveMinuteCandle() {
        feedMinuteCandle("NIFTY", 0, "100", "105", "98", "102");
        feedMinuteCandle("NIFTY", 1, "102", "108", "101", "107");
        feedMinuteCandle("NIFTY", 2, "107", "110", "104", "106");
        feedMinuteCandle("NIFTY", 3, "106", "109", "103", "105");
        feedMinuteCandle("NIFTY", 4, "105", "112", "104", "111");

        assertTrue(emitted.isEmpty(), "should not emit until the window actually rolls over");

        feedMinuteCandle("NIFTY", 5, "111", "113", "110", "112");

        assertEquals(1, emitted.size());
        Candle fiveMin = emitted.get(0);
        assertEquals(new BigDecimal("100"), fiveMin.getOpen());
        assertEquals(new BigDecimal("112"), fiveMin.getHigh());
        assertEquals(new BigDecimal("98"), fiveMin.getLow());
        assertEquals(new BigDecimal("111"), fiveMin.getClose());
        assertEquals(WINDOW_START, fiveMin.getWindowStart());
        assertEquals("5m", fiveMin.getTimeframe());
    }

    @Test
    void skipsAnIncompleteWindowInsteadOfEmittingAPartialCandle() {
        feedMinuteCandle("NIFTY", 0, "100", "105", "98", "102");
        feedMinuteCandle("NIFTY", 1, "102", "108", "101", "107");
        feedMinuteCandle("NIFTY", 5, "111", "113", "110", "112");

        assertTrue(emitted.isEmpty(), "an incomplete window must not produce a misleading aggregated candle");
    }

    @Test
    void tracksSymbolsIndependently() {
        feedMinuteCandle("NIFTY", 0, "100", "105", "98", "102");
        feedMinuteCandle("BANKNIFTY", 0, "500", "505", "498", "502");

        for (int i = 1; i <= 5; i++) {
            feedMinuteCandle("NIFTY", i, "100", "100", "100", "100");
            feedMinuteCandle("BANKNIFTY", i, "500", "500", "500", "500");
        }

        assertEquals(2, emitted.size());
        assertTrue(emitted.stream().anyMatch(c -> c.getSymbol().equals("NIFTY")));
        assertTrue(emitted.stream().anyMatch(c -> c.getSymbol().equals("BANKNIFTY")));
    }

    private void feedMinuteCandle(String symbol, int minuteOffset, String open, String high, String low, String close) {
        Instant start = WINDOW_START.plusSeconds(60L * minuteOffset);
        aggregator.onCandleClosed(new Candle(symbol, "1m", start, start.plusSeconds(60),
                new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), 100L));
    }
}