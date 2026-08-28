package com.tradingplatform.pattern.impl;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.pattern.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bullish Engulfing: Bearish candle followed by larger bullish candle that engulfs it
 * Reliability: 75%
 */
public class BullishEngulfingPattern extends BasePattern {

    @Override
    public String getName() {
        return "Bullish Engulfing";
    }

    @Override
    public PatternType getType() {
        return PatternType.BULLISH_REVERSAL;
    }

    @Override
    public int getRequiredCandles() {
        return 2;
    }

    @Override
    public PatternMatch detect(List<Candle> candles, int currentIndex) {
        if (currentIndex < 1 || currentIndex >= candles.size()) {
            return null;
        }

        Candle prev = candles.get(currentIndex - 1);
        Candle curr = candles.get(currentIndex);

        // Pattern rules:
        // 1. Previous candle is bearish
        // 2. Current candle is bullish
        // 3. Current open <= previous close
        // 4. Current close >= previous open

        boolean prevBearish = isBearish(prev);
        boolean currBullish = isBullish(curr);
        boolean opensLower = isLess(curr.getOpen(), prev.getClose()) ||
                isEqual(curr.getOpen(), prev.getClose());
        boolean closesHigher = isGreater(curr.getClose(), prev.getOpen()) ||
                isEqual(curr.getClose(), prev.getOpen());

        if (prevBearish && currBullish && opensLower && closesHigher) {
            double confidence = calculateConfidence(prev, curr);

            return new PatternMatch(
                    getName(),
                    getType(),
                    confidence,
                    currentIndex,
                    List.of(prev, curr),
                    String.format("Bullish engulfing at %s (body ratio: %.2fx)",
                            curr.getClose(),
                            ratio(getBody(curr), getBody(prev)))
            );
        }

        return null;
    }

    private double calculateConfidence(Candle prev, Candle curr) {
        // Higher confidence if:
        // - Engulfing is significant (2x+ body size)
        // - Volume is higher on engulfing candle

        BigDecimal prevBody = getBody(prev);
        BigDecimal currBody = getBody(curr);

        double bodyRatio = ratio(currBody, prevBody.max(BigDecimal.valueOf(0.01)));
        double volumeRatio = (double) curr.getVolume() / Math.max(prev.getVolume(), 1.0);

        double confidence = 0.60; // Base confidence

        if (bodyRatio >= 2.0) confidence += 0.15;
        if (bodyRatio >= 3.0) confidence += 0.10;
        if (volumeRatio >= 1.5) confidence += 0.10;

        return Math.min(0.95, confidence);
    }

    @Override
    public double getReliabilityScore() {
        return 0.75;
    }
}