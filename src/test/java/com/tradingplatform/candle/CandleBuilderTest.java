package com.tradingplatform.candle;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.tick.Tick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandleBuilderTest {

    private static final Instant WINDOW_1_START = Instant.parse("2024-01-01T09:15:00Z");

    private CandleBuilder candleBuilder;
    private List<Candle> closedCandles;

    @BeforeEach
    void setUp() {
        candleBuilder = new CandleBuilder(Duration.ofMinutes(1), "1m");
        closedCandles = new ArrayList<>();
        candleBuilder.addListener(closedCandles::add);
    }

    @Test
    void doesNotEmitAnythingUntilAWindowActuallyCloses() {
        candleBuilder.onTick(tick("NIFTY", WINDOW_1_START.plusSeconds(5), "100.00"));
        candleBuilder.onTick(tick("NIFTY", WINDOW_1_START.plusSeconds(30), "101.00"));

        assertTrue(closedCandles.isEmpty(),
                "no candle should be emitted while ticks are still landing in the same window");
    }

    @Test
    void buildsCorrectOhlcvFromMultipleTicksInSameWindow() {
        candleBuilder.onTick(tick("NIFTY", WINDOW_1_START.plusSeconds(1), "100.00"));
        candleBuilder.onTick(tick("NIFTY", WINDOW_1_START.plusSeconds(10), "105.00"));
        candleBuilder.onTick(tick("NIFTY", WINDOW_1_START.plusSeconds(20), "98.00"));
        candleBuilder.onTick(tick("NIFTY", WINDOW_1_START.plusSeconds(30), "102.00"));

        candleBuilder.onTick(tick("NIFTY", WINDOW_1_START.plusSeconds(65), "103.00"));

        assertEquals(1, closedCandles.size());
        Candle candle = closedCandles.get(0);
        assertEquals(new BigDecimal("100.00"), candle.getOpen());
        assertEquals(new BigDecimal("105.00"), candle.getHigh());
        assertEquals(new BigDecimal("98.00"), candle.getLow());
        assertEquals(new BigDecimal("102.00"), candle.getClose());
        assertEquals(WINDOW_1_START, candle.getWindowStart());
        assertEquals("1m", candle.getTimeframe());
    }

    @Test
    void tracksSeparateCandlesPerSymbolIndependently() {
        candleBuilder.onTick(tick("NIFTY", WINDOW_1_START.plusSeconds(1), "100.00"));
        candleBuilder.onTick(tick("BANKNIFTY", WINDOW_1_START.plusSeconds(1), "500.00"));

        candleBuilder.onTick(tick("NIFTY", WINDOW_1_START.plusSeconds(65), "110.00"));
        candleBuilder.onTick(tick("BANKNIFTY", WINDOW_1_START.plusSeconds(65), "550.00"));

        assertEquals(2, closedCandles.size());
        assertTrue(closedCandles.stream().anyMatch(c -> c.getSymbol().equals("NIFTY")));
        assertTrue(closedCandles.stream().anyMatch(c -> c.getSymbol().equals("BANKNIFTY")));
    }

    @Test
    void dropsLateTicksForAWindowThatAlreadyClosedWithoutCorruptingTheNewCandle() {
        candleBuilder.onTick(tick("NIFTY", WINDOW_1_START.plusSeconds(1), "100.00"));
        candleBuilder.onTick(tick("NIFTY", WINDOW_1_START.plusSeconds(65), "110.00"));
        candleBuilder.onTick(tick("NIFTY", WINDOW_1_START.plusSeconds(50), "999.00"));
        candleBuilder.onTick(tick("NIFTY", WINDOW_1_START.plusSeconds(125), "111.00"));

        assertEquals(2, closedCandles.size());
        Candle window2 = closedCandles.get(1);
        assertEquals(new BigDecimal("110.00"), window2.getOpen());
        assertTrue(window2.getHigh().compareTo(new BigDecimal("999.00")) < 0,
                "late tick's price must not leak into the next window's candle");
    }

    private static Tick tick(String symbol, Instant timestamp, String price) {
        return new Tick(symbol, timestamp, new BigDecimal(price), 10L);
    }
}