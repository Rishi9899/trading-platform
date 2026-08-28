package com.tradingplatform.pattern.impl;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.pattern.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Shooting Star: Small body at bottom, long upper shadow (2x+ body), minimal lower shadow
 * Bearish reversal at top of uptrend
 * Reliability: 68%
 */
public class ShootingStarPattern extends BasePattern {

    @Override
    public String getName() {
        return "Shooting Star";
    }

    @Override
    public PatternType getType() {
        return PatternType.BEARISH_REVERSAL;
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

        // Pattern rules:
        // 1. Small body (< 30% of range)
        // 2. Long upper shadow (2x+ body)
        // 3. Little to no lower shadow

        BigDecimal body = getBody(c);
        BigDecimal upperShadow = getUpperShadow(c);
        BigDecimal lowerShadow = getLowerShadow(c);
        BigDecimal range = getTotalRange(c);

        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        boolean smallBody = isSmallBody(c);
        boolean longUpperShadow = hasLongUpperShadow(c);
        boolean smallLowerShadow = isLess(lowerShadow, body);

        if (smallBody && longUpperShadow && smallLowerShadow) {
            double confidence = calculateConfidence(c);

            return new PatternMatch(
                    getName(),
                    getType(),
                    confidence,
                    currentIndex,
                    List.of(c),
                    String.format("Shooting Star at %s (upper shadow: %.2fx body)",
                            c.getClose(),
                            ratio(upperShadow, body))
            );
        }

        return null;
    }

    private double calculateConfidence(Candle c) {
        BigDecimal body = getBody(c);
        BigDecimal upperShadow = getUpperShadow(c);

        double shadowRatio = ratio(upperShadow, body);

        double confidence = 0.58;

        if (shadowRatio >= 3.0) confidence += 0.12;
        if (shadowRatio >= 4.0) confidence += 0.08;

        // Higher confidence if bearish (close < open)
        if (isBearish(c)) confidence += 0.05;

        return Math.min(0.88, confidence);
    }

    @Override
    public double getReliabilityScore() {
        return 0.68;
    }
}