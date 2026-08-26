package com.tradingplatform.strategy.impl;

import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.indicator.MovingAverageConvergenceDivergence;
import com.tradingplatform.indicator.MovingAverageConvergenceDivergence.MacdValue;
import com.tradingplatform.strategy.MarketContext;
import com.tradingplatform.strategy.StrategyDecision;
import com.tradingplatform.strategy.TradingStrategy;

import java.math.BigDecimal;

/**
 * Buys when the MACD line crosses above its own signal line (bullish
 * momentum shift), sells on a cross below. Confidence scales with how
 * far the histogram (macd - signal) has moved past zero, relative to
 * `histogramScale` - a rough proxy for how decisive the crossover was,
 * not a statistically calibrated probability.
 */
public class MacdMomentumStrategy implements TradingStrategy {

    private final MovingAverageConvergenceDivergence macd;
    private final BigDecimal histogramScale;

    private BigDecimal previousMacdLine;
    private BigDecimal previousSignalLine;

    public MacdMomentumStrategy(int fastPeriod, int slowPeriod, int signalPeriod, BigDecimal histogramScale) {
        this.macd = new MovingAverageConvergenceDivergence(fastPeriod, slowPeriod, signalPeriod);
        this.histogramScale = histogramScale;
    }

    @Override
    public StrategyDecision evaluate(MarketContext context) {
        var candle = context.currentCandle();
        macd.update(candle);

        if (!macd.isReady()) {
            return null; // Warming up
        }

        MacdValue current = macd.value().orElseThrow();

        if (previousMacdLine == null || previousSignalLine == null) {
            previousMacdLine = current.macdLine();
            previousSignalLine = current.signalLine();
            return null; // Establish baseline candle
        }

        boolean bullishCrossover = previousMacdLine.compareTo(previousSignalLine) <= 0
                && current.macdLine().compareTo(current.signalLine()) > 0;
        boolean bearishCrossover = previousMacdLine.compareTo(previousSignalLine) >= 0
                && current.macdLine().compareTo(current.signalLine()) < 0;

        StrategyDecision decision;
        if (bullishCrossover) {
            decision = new StrategyDecision(SignalType.BUY, candle.getClose(),
                    confidence(current.histogram()),
                    "MACD line crossed above signal line, histogram=%.4f".formatted(current.histogram()));
        } else if (bearishCrossover) {
            decision = new StrategyDecision(SignalType.SELL, candle.getClose(),
                    confidence(current.histogram()),
                    "MACD line crossed below signal line, histogram=%.4f".formatted(current.histogram()));
        } else {
            decision = new StrategyDecision(SignalType.HOLD, candle.getClose(), 0.0,
                    "no new MACD/signal crossover since previous candle");
        }

        previousMacdLine = current.macdLine();
        previousSignalLine = current.signalLine();
        return decision;
    }

    private double confidence(BigDecimal histogram) {
        double baseConfidence = 0.55;
        double bonus = Math.min(Math.max(histogram.abs().doubleValue() / histogramScale.doubleValue(), 0.0), 0.44);
        return Math.min(baseConfidence + bonus, 0.99);
    }
}
