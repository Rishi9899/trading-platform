package com.tradingplatform.pattern.impl;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.pattern.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Evening Star: 3-candle bearish reversal pattern
 * 1. Large bullish candle
 * 2. Small body (star) - gaps up
 * 3. Large bearish candle - closes below first candle's midpoint
 * Reliability: 78%
 */
public class EveningStarPattern extends BasePattern {

    @Override
    public String getName() {
        return "Evening Star";
    }

    @Override
    public PatternType getType() {
        return PatternType.BEARISH_REVERSAL;
    }

    @Override
    public int getRequiredCandles() {
        return 3;
    }

    @Override
    public PatternMatch detect(List<Candle> candles, int currentIndex) {
        if (currentIndex < 2 || currentIndex >= candles.size()) {
            return null;
        }

        Candle first = candles.get(currentIndex - 2);   // Large bullish
        Candle star = candles.get(currentIndex - 1);    // Small body (star)
        Candle third = candles.get(currentIndex);       // Large bearish

        // Pattern rules:
        // 1. First: Large bullish candle
        // 2. Star: Small body (< 30% of first body), gaps up
        // 3. Third: Large bearish, closes below first's midpoint

        boolean firstBullish = isBullish(first);
        boolean firstLarge = getBodyPercent(first) > 0.6;

        BigDecimal firstBody = getBody(first);
        BigDecimal starBody = getBody(star);
        boolean starSmall = isLess(starBody, firstBody.multiply(BigDecimal.valueOf(0.3)));
        boolean starGapsUp = isGreater(star.getLow(), first.getClose());

        boolean thirdBearish = isBearish(third);
        boolean thirdLarge = getBodyPercent(third) > 0.6;

        BigDecimal firstMidpoint = first.getOpen().add(first.getClose())
                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        boolean thirdClosesBelowMidpoint = isLess(third.getClose(), firstMidpoint);

        if (firstBullish && firstLarge && starSmall && starGapsUp &&
                thirdBearish && thirdLarge && thirdClosesBelowMidpoint) {

            double confidence = calculateConfidence(first, star, third);

            return new PatternMatch(
                    getName(),
                    getType(),
                    confidence,
                    currentIndex,
                    List.of(first, star, third),
                    String.format("Evening Star at %s (reversal from %s)",
                            third.getClose(), first.getOpen())
            );
        }

        return null;
    }

    private double calculateConfidence(Candle first, Candle star, Candle third) {
        BigDecimal firstBody = getBody(first);
        BigDecimal starBody = getBody(star);
        BigDecimal thirdBody = getBody(third);

        double starSmallness = ratio(starBody, firstBody);
        double thirdStrength = ratio(thirdBody, firstBody);

        double confidence = 0.65; // Base

        // Smaller star = stronger pattern
        if (starSmallness < 0.15) confidence += 0.10;
        if (starSmallness < 0.10) confidence += 0.05;

        // Larger third candle = stronger reversal
        if (thirdStrength >= 0.8) confidence += 0.08;
        if (thirdStrength >= 1.0) confidence += 0.07;

        return Math.min(0.95, confidence);
    }

    @Override
    public double getReliabilityScore() {
        return 0.78;
    }
}