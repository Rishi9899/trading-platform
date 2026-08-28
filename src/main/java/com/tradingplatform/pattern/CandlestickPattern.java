package com.tradingplatform.pattern;

import com.tradingplatform.domain.candle.Candle;

import java.util.List;

/**
 * Interface for all candlestick pattern detectors
 */
public interface CandlestickPattern {

    /**
     * Pattern name (e.g., "Bullish Engulfing")
     */
    String getName();

    /**
     * Pattern type (reversal, continuation, etc.)
     */
    PatternType getType();

    /**
     * Number of candles required (1, 2, 3, or 5+)
     */
    int getRequiredCandles();

    /**
     * Detect pattern in candle history
     * @param candles Complete candle history
     * @param currentIndex Index to check for pattern
     * @return PatternMatch if detected, null otherwise
     */
    PatternMatch detect(List<Candle> candles, int currentIndex);

    /**
     * Historical reliability score (0.0 to 1.0)
     */
    double getReliabilityScore();
}