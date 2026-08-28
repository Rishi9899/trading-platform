package com.tradingplatform.pattern.impl;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.pattern.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bearish Engulfing: Bullish candle followed by larger bearish candle that engulfs it
 * Reliability: 75%
 */
public class BearishEngulfingPattern extends BasePattern {

    @Override
    public String getName() {
        return "Bearish Engulfing";
    }

    @Override
    public PatternType getType() {
        return PatternType.BEARISH_REVERSAL;
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
        // 1. Previous candle is bullish
        // 2. Current candle is bearish
        // 3. Current open >= previous close
        // 4. Current close <= previous open

        boolean prevBullish = isBullish(prev);
        boolean currBearish = isBearish(curr);
        boolean opensHigher = isGreater(curr.getOpen(), prev.getClose()) ||
                isEqual(curr.getOpen(), prev.getClose());
        boolean closesLower = isLess(curr.getClose(), prev.getOpen()) ||
                isEqual(curr.getClose(), prev.getOpen());

        if (prevBullish && currBearish && opensHigher && closesLower) {
            double confidence = calculateConfidence(prev, curr);

            return new PatternMatch(
                    getName(),
                    getType(),
                    confidence,
                    currentIndex,
                    List.of(prev, curr),
                    String.format("Bearish engulfing at %s (body ratio: %.2fx)",
                            curr.getClose(),
                            ratio(getBody(curr), getBody(prev)))
            );
        }

        return null;
    }

    private double calculateConfidence(Candle prev, Candle curr) {
        BigDecimal prevBody = getBody(prev);
        BigDecimal currBody = getBody(curr);

        double bodyRatio = ratio(currBody, prevBody.max(BigDecimal.valueOf(0.01)));
        double volumeRatio = (double) curr.getVolume() / Math.max(prev.getVolume(), 1.0);

        double confidence = 0.60; // Base

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