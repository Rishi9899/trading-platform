package com.tradingplatform.candle;

import java.time.Duration;
import java.time.Instant;

/**
 * Shared epoch-aligned window math, used by both CandleBuilder (ticks ->
 * base candles) and CandleAggregator (base candles -> derived timeframes)
 * so alignment logic can't drift out of sync between the two.
 */
public final class CandleWindow {

    private CandleWindow() {
    }

    public static Instant alignDown(Instant timestamp, Duration timeframe) {
        long timeframeSeconds = timeframe.getSeconds();
        long epochSeconds = timestamp.getEpochSecond();
        long alignedEpochSeconds = (epochSeconds / timeframeSeconds) * timeframeSeconds;
        return Instant.ofEpochSecond(alignedEpochSeconds);
    }
}