package com.tradingplatform.strategy.impl;

import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.indicator.ExponentialMovingAverage;
import com.tradingplatform.indicator.RelativeStrengthIndex;
import com.tradingplatform.strategy.MarketContext;
import com.tradingplatform.strategy.StrategyDecision;
import com.tradingplatform.strategy.TradingStrategy;

import java.math.BigDecimal;

public class EmaCrossoverStrategy implements TradingStrategy {

    private final ExponentialMovingAverage emaFast;
    private final ExponentialMovingAverage emaSlow;
    private final RelativeStrengthIndex rsi;
    private final BigDecimal buyRsiThreshold;
    private final BigDecimal sellRsiThreshold;

    private BigDecimal previousFast;
    private BigDecimal previousSlow;

    public EmaCrossoverStrategy(int fastPeriod, int slowPeriod, int rsiPeriod,
                                BigDecimal buyRsiThreshold, BigDecimal sellRsiThreshold) {
        if (fastPeriod >= slowPeriod) {
            throw new IllegalArgumentException(
                    "fastPeriod (" + fastPeriod + ") must be less than slowPeriod (" + slowPeriod + ")");
        }
        this.emaFast = new ExponentialMovingAverage(fastPeriod);
        this.emaSlow = new ExponentialMovingAverage(slowPeriod);
        this.rsi = new RelativeStrengthIndex(rsiPeriod);
        this.buyRsiThreshold = buyRsiThreshold;
        this.sellRsiThreshold = sellRsiThreshold;
    }

    @Override
    public StrategyDecision evaluate(MarketContext context) {
        var candle = context.currentCandle();

        emaFast.update(candle);
        emaSlow.update(candle);
        rsi.update(candle);

        if (!emaFast.isReady() || !emaSlow.isReady() || !rsi.isReady()) {
            return null; // Warming up
        }

        BigDecimal currentFast = emaFast.value().orElseThrow();
        BigDecimal currentSlow = emaSlow.value().orElseThrow();
        BigDecimal rsiValue = rsi.value().orElseThrow();

        if (previousFast == null || previousSlow == null) {
            previousFast = currentFast;
            previousSlow = currentSlow;
            return null; // Establish baseline candle
        }

        boolean bullishCrossover = previousFast.compareTo(previousSlow) <= 0
                && currentFast.compareTo(currentSlow) > 0;
        boolean bearishCrossover = previousFast.compareTo(previousSlow) >= 0
                && currentFast.compareTo(currentSlow) < 0;

        StrategyDecision decision;
        if (bullishCrossover && rsiValue.compareTo(buyRsiThreshold) > 0) {
            double confidence = calculateConfidence(rsiValue, buyRsiThreshold, true);
            decision = new StrategyDecision(SignalType.BUY, candle.getClose(), confidence,
                    "bullish EMA crossover, RSI=%.2f > %.2f".formatted(rsiValue, buyRsiThreshold));

        } else if (bearishCrossover && rsiValue.compareTo(sellRsiThreshold) < 0) {
            double confidence = calculateConfidence(rsiValue, sellRsiThreshold, false);
            decision = new StrategyDecision(SignalType.SELL, candle.getClose(), confidence,
                    "bearish EMA crossover, RSI=%.2f < %.2f".formatted(rsiValue, sellRsiThreshold));

        } else if (bullishCrossover) {
            decision = new StrategyDecision(SignalType.HOLD, candle.getClose(), 0.0,
                    "bullish EMA crossover but RSI=%.2f did not clear %.2f".formatted(rsiValue, buyRsiThreshold));

        } else if (bearishCrossover) {
            decision = new StrategyDecision(SignalType.HOLD, candle.getClose(), 0.0,
                    "bearish EMA crossover but RSI=%.2f did not clear %.2f".formatted(rsiValue, sellRsiThreshold));

        } else {
            decision = new StrategyDecision(SignalType.HOLD, candle.getClose(), 0.0,
                    "no new crossover - EMA relationship unchanged since previous candle");
        }

        previousFast = currentFast;
        previousSlow = currentSlow;
        return decision;
    }

    private double calculateConfidence(BigDecimal rsi, BigDecimal threshold, boolean isBuy) {
        double baseConfidence = 0.60;
        double diff = isBuy ? rsi.subtract(threshold).doubleValue() : threshold.subtract(rsi).doubleValue();
        double bonus = Math.min(Math.max(diff / 30.0, 0.0), 0.39);
        return Math.min(baseConfidence + bonus, 0.99);
    }
}