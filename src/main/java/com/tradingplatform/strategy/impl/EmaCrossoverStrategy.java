package com.tradingplatform.strategy.impl;

import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.indicator.ExponentialMovingAverage;
import com.tradingplatform.indicator.RelativeStrengthIndex;
import com.tradingplatform.strategy.MarketContext;
import com.tradingplatform.strategy.StrategyDecision;
import com.tradingplatform.strategy.TradingStrategy;

import java.math.BigDecimal;

/**
 * Detects an actual EMA crossover - a change in which EMA is on top
 * between the previous candle and this one - not merely "fast is
 * currently above slow" (which would re-fire every candle for as long
 * as an uptrend continues).
 *
 * Bullish crossover: previousFast <= previousSlow AND currentFast > currentSlow
 * Bearish crossover: previousFast >= previousSlow AND currentFast < currentSlow
 *
 * BUY requires a bullish crossover AND RSI > buyRsiThreshold.
 * SELL requires a bearish crossover AND RSI < sellRsiThreshold.
 * Everything else - no crossover, or a crossover the RSI filter rejects
 * - is HOLD.
 *
 * One instance of this class is created per registered StrategyInstance
 * (see StrategyRegistryConfig), so each owns private, independent
 * indicator and previous-state fields - safe to hold mutable state here,
 * no cross-contamination between instances with different parameters or
 * watching different symbols/timeframes.
 */
public class EmaCrossoverStrategy implements TradingStrategy {

    private final ExponentialMovingAverage emaFast;
    private final ExponentialMovingAverage emaSlow;
    private final RelativeStrengthIndex rsi;
    private final BigDecimal buyRsiThreshold;
    private final BigDecimal sellRsiThreshold;

    /**
     * The EMA values as of the previous evaluated candle, needed to
     * detect a crossover (a *change* in relationship, not a snapshot).
     * Null until the first candle where all indicators are ready - that
     * first candle only establishes this baseline, it must not itself
     * be treated as a crossover.
     */
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
            return null; // still warming up - no opinion yet, no signal recorded
        }

        BigDecimal currentFast = emaFast.value().orElseThrow();
        BigDecimal currentSlow = emaSlow.value().orElseThrow();
        BigDecimal rsiValue = rsi.value().orElseThrow();

        if (previousFast == null || previousSlow == null) {
            // First candle where every indicator is ready: this only
            // establishes the baseline for crossover comparison. Firing a
            // signal here would be comparing "current" against nothing,
            // which is exactly the false-positive-on-readiness bug this
            // fix removes.
            previousFast = currentFast;
            previousSlow = currentSlow;
            return null;
        }

        boolean bullishCrossover = previousFast.compareTo(previousSlow) <= 0
                && currentFast.compareTo(currentSlow) > 0;
        boolean bearishCrossover = previousFast.compareTo(previousSlow) >= 0
                && currentFast.compareTo(currentSlow) < 0;

        StrategyDecision decision;
        if (bullishCrossover && rsiValue.compareTo(buyRsiThreshold) > 0) {
            decision = new StrategyDecision(SignalType.BUY, candle.getClose(), null,
                    "bullish EMA crossover, RSI=%.2f > %.2f".formatted(rsiValue, buyRsiThreshold));
        } else if (bearishCrossover && rsiValue.compareTo(sellRsiThreshold) < 0) {
            decision = new StrategyDecision(SignalType.SELL, candle.getClose(), null,
                    "bearish EMA crossover, RSI=%.2f < %.2f".formatted(rsiValue, sellRsiThreshold));
        } else if (bullishCrossover) {
            decision = new StrategyDecision(SignalType.HOLD, candle.getClose(), null,
                    "bullish EMA crossover but RSI=%.2f did not clear %.2f".formatted(rsiValue, buyRsiThreshold));
        } else if (bearishCrossover) {
            decision = new StrategyDecision(SignalType.HOLD, candle.getClose(), null,
                    "bearish EMA crossover but RSI=%.2f did not clear %.2f".formatted(rsiValue, sellRsiThreshold));
        } else {
            decision = new StrategyDecision(SignalType.HOLD, candle.getClose(), null,
                    "no new crossover - EMA relationship unchanged since previous candle");
        }

        previousFast = currentFast;
        previousSlow = currentSlow;
        return decision;
    }
}