package com.tradingplatform.strategy.impl;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.strategy.MarketContext;
import com.tradingplatform.strategy.StrategyDecision;
import com.tradingplatform.strategy.TradingStrategy;

import java.math.BigDecimal;
import java.util.List;

/**
 * Donchian Channel Breakout (Turtle Trader style).
 *
 * BUY when close breaks above the highest high of the last N candles.
 * SELL when close breaks below the lowest low of the last N candles.
 *
 * Uses a shorter exit channel (exitPeriod) to avoid giving back too
 * much profit — exit BUY positions when close breaks below exit-period
 * low, exit SELL positions when close breaks above exit-period high.
 *
 * Requires lookback of `entryPeriod` candles in recentCandles — benefits
 * from 500 candle history to fully prime a 50-period channel.
 *
 * ATR filter: only takes breakout if current candle range > minAtrFraction * ATR
 * to avoid false breakouts during dead markets.
 */
public class DonchianBreakoutStrategy implements TradingStrategy {

    private final int entryPeriod;     // e.g., 55 candles for entry channel
    private final int exitPeriod;      // e.g., 20 candles for exit channel
    private final int atrPeriod;       // e.g., 20 for ATR calculation
    private final double minAtrFraction; // minimum range/ATR ratio to confirm breakout

    private SignalType lastSignal = null; // Prevent repeated same-direction signals

    public DonchianBreakoutStrategy(int entryPeriod, int exitPeriod, int atrPeriod, double minAtrFraction) {
        this.entryPeriod = entryPeriod;
        this.exitPeriod = exitPeriod;
        this.atrPeriod = atrPeriod;
        this.minAtrFraction = minAtrFraction;
    }

    @Override
    public StrategyDecision evaluate(MarketContext context) {
        List<Candle> candles = context.recentCandles();
        Candle current = context.currentCandle();

        if (candles.size() < entryPeriod + 1) {
            return null; // Not enough history
        }

        // Calculate Donchian channels from previous candles (exclude current)
        int size = candles.size();
        BigDecimal entryHigh = highestHigh(candles, size - 1 - entryPeriod, size - 1);
        BigDecimal entryLow = lowestLow(candles, size - 1 - entryPeriod, size - 1);
        BigDecimal exitHigh = highestHigh(candles, size - 1 - Math.min(exitPeriod, size - 1), size - 1);
        BigDecimal exitLow = lowestLow(candles, size - 1 - Math.min(exitPeriod, size - 1), size - 1);

        // ATR filter
        BigDecimal atr = calculateATR(candles, Math.min(atrPeriod, size - 1));
        BigDecimal currentRange = current.getHigh().subtract(current.getLow());
        boolean volatilityConfirmed = atr.compareTo(BigDecimal.ZERO) > 0
                && currentRange.doubleValue() >= minAtrFraction * atr.doubleValue();

        BigDecimal close = current.getClose();

        // Entry signals
        if (close.compareTo(entryHigh) > 0 && volatilityConfirmed && lastSignal != SignalType.BUY) {
            lastSignal = SignalType.BUY;
            double confidence = calculateConfidence(close, entryHigh, atr);
            return new StrategyDecision(SignalType.BUY, close, confidence,
                    "Donchian breakout: close %.2f > %d-period high %.2f, ATR=%.2f"
                            .formatted(close.doubleValue(), entryPeriod, entryHigh.doubleValue(), atr.doubleValue()));
        }

        if (close.compareTo(entryLow) < 0 && volatilityConfirmed && lastSignal != SignalType.SELL) {
            lastSignal = SignalType.SELL;
            double confidence = calculateConfidence(entryLow, close, atr);
            return new StrategyDecision(SignalType.SELL, close, confidence,
                    "Donchian breakdown: close %.2f < %d-period low %.2f, ATR=%.2f"
                            .formatted(close.doubleValue(), entryPeriod, entryLow.doubleValue(), atr.doubleValue()));
        }

        // Exit signals (use shorter exit channel)
        if (lastSignal == SignalType.BUY && close.compareTo(exitLow) < 0) {
            lastSignal = null;
            return new StrategyDecision(SignalType.SELL, close, 0.50,
                    "Donchian exit: close %.2f < %d-period exit low %.2f"
                            .formatted(close.doubleValue(), exitPeriod, exitLow.doubleValue()));
        }

        if (lastSignal == SignalType.SELL && close.compareTo(exitHigh) > 0) {
            lastSignal = null;
            return new StrategyDecision(SignalType.BUY, close, 0.50,
                    "Donchian exit: close %.2f > %d-period exit high %.2f"
                            .formatted(close.doubleValue(), exitPeriod, exitHigh.doubleValue()));
        }

        return new StrategyDecision(SignalType.HOLD, close, 0.0,
                "close within Donchian channel [%.2f - %.2f]".formatted(entryLow.doubleValue(), entryHigh.doubleValue()));
    }

    private BigDecimal highestHigh(List<Candle> candles, int fromIndex, int toIndex) {
        BigDecimal highest = candles.get(Math.max(fromIndex, 0)).getHigh();
        for (int i = Math.max(fromIndex, 0) + 1; i < toIndex; i++) {
            BigDecimal h = candles.get(i).getHigh();
            if (h.compareTo(highest) > 0) highest = h;
        }
        return highest;
    }

    private BigDecimal lowestLow(List<Candle> candles, int fromIndex, int toIndex) {
        BigDecimal lowest = candles.get(Math.max(fromIndex, 0)).getLow();
        for (int i = Math.max(fromIndex, 0) + 1; i < toIndex; i++) {
            BigDecimal l = candles.get(i).getLow();
            if (l.compareTo(lowest) < 0) lowest = l;
        }
        return lowest;
    }

    private BigDecimal calculateATR(List<Candle> candles, int period) {
        int size = candles.size();
        int start = Math.max(size - period - 1, 0);
        double sum = 0;
        int count = 0;

        for (int i = start + 1; i < size; i++) {
            Candle c = candles.get(i);
            Candle prev = candles.get(i - 1);
            double tr = Math.max(
                    c.getHigh().subtract(c.getLow()).doubleValue(),
                    Math.max(
                            Math.abs(c.getHigh().subtract(prev.getClose()).doubleValue()),
                            Math.abs(c.getLow().subtract(prev.getClose()).doubleValue())
                    )
            );
            sum += tr;
            count++;
        }
        return count > 0 ? BigDecimal.valueOf(sum / count) : BigDecimal.ZERO;
    }

    private double calculateConfidence(BigDecimal breakPrice, BigDecimal channelEdge, BigDecimal atr) {
        if (atr.doubleValue() <= 0) return 0.55;
        double breachDistance = Math.abs(breakPrice.subtract(channelEdge).doubleValue());
        double ratio = breachDistance / atr.doubleValue();
        double bonus = Math.min(ratio * 0.3, 0.44);
        return Math.min(0.55 + bonus, 0.99);
    }
}