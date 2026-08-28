package com.tradingplatform.pattern.impl;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.pattern.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Hammer: Small body at top, long lower shadow (2x+ body), minimal upper shadow
 * Bullish reversal at bottom of downtrend
 *
 * Characteristics:
 * - Small real body (any color, but bullish is stronger)
 * - Long lower shadow (at least 2x the body)
 * - Little to no upper shadow
 * - Body at upper end of trading range
 *
 * Reliability: 70%
 */
public class HammerPattern extends BasePattern {

    @Override
    public String getName() {
        return "Hammer";
    }

    @Override
    public PatternType getType() {
        return PatternType.BULLISH_REVERSAL;
    }

    @Override
    public int getRequiredCandles() {
        return 1;
    }

    @Override
    public PatternMatch detect(List<Candle> candles, int currentIndex) {
        if (currentIndex < 0 || currentIndex >= candles.size()) {
            return null;
        }

        Candle c = candles.get(currentIndex);

        BigDecimal body = getBody(c);
        BigDecimal lowerShadow = getLowerShadow(c);
        BigDecimal upperShadow = getUpperShadow(c);
        BigDecimal range = getTotalRange(c);

        // Avoid division by zero
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        // Pattern rules:
        // 1. Small body (< 30% of total range)
        // 2. Long lower shadow (at least 2x the body length)
        // 3. Little to no upper shadow (less than body length)

        boolean smallBody = isSmallBody(c);  // Body < 30% of range
        boolean longLowerShadow = hasLongLowerShadow(c);  // Lower shadow > 2x body
        boolean smallUpperShadow = isLess(upperShadow, body) ||
                isEqual(upperShadow, body);

        if (smallBody && longLowerShadow && smallUpperShadow) {
            double confidence = calculateConfidence(c);

            return new PatternMatch(
                    getName(),
                    getType(),
                    confidence,
                    currentIndex,
                    List.of(c),
                    String.format("Hammer at %s (lower shadow: %.2fx body, body: %.1f%% of range)",
                            c.getClose(),
                            ratio(lowerShadow, body),
                            getBodyPercent(c) * 100)
            );
        }

        return null;
    }

    private double calculateConfidence(Candle c) {
        BigDecimal body = getBody(c);
        BigDecimal lowerShadow = getLowerShadow(c);
        BigDecimal upperShadow = getUpperShadow(c);

        // Calculate shadow to body ratios
        double lowerShadowRatio = ratio(lowerShadow, body);
        double bodyPercent = getBodyPercent(c);

        double confidence = 0.60; // Base confidence

        // Longer lower shadow = stronger signal
        if (lowerShadowRatio >= 3.0) confidence += 0.10;
        if (lowerShadowRatio >= 4.0) confidence += 0.05;

        // Smaller body = cleaner pattern
        if (bodyPercent < 0.20) confidence += 0.08;
        if (bodyPercent < 0.15) confidence += 0.05;

        // Bullish hammer (close > open) is slightly stronger
        if (isBullish(c)) confidence += 0.05;

        // Almost no upper shadow = better
        if (isLess(upperShadow, body.multiply(BigDecimal.valueOf(0.1)))) {
            confidence += 0.05;
        }

        return Math.min(0.92, confidence);
    }

    @Override
    public double getReliabilityScore() {
        return 0.70;
    }
}