package com.tradingplatform.pattern;

import com.tradingplatform.domain.candle.Candle;

import java.util.List;

/**
 * Result of pattern detection
 */
public record PatternMatch(
        String patternName,
        PatternType type,
        double confidence,
        int detectedAtIndex,
        List<Candle> matchedCandles,
        String description
) {
    public PatternMatch(String patternName, PatternType type, double confidence, int detectedAtIndex, List<Candle> matchedCandles) {
        this(patternName, type, confidence, detectedAtIndex, matchedCandles, "");
    }
}