package com.tradingplatform.strategy.impl;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.strategy.MarketContext;
import com.tradingplatform.strategy.StrategyDecision;
import com.tradingplatform.strategy.TradingStrategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Optional;

/**
 * Wraps EmaCrossoverStrategy's own-timeframe signal with a higher-timeframe
 * trend filter: a BUY is only kept if the confirmation timeframe's recent
 * closes are trending up (last close above their simple average), a SELL
 * only kept if trending down. A signal that contradicts the higher-timeframe
 * trend is downgraded to HOLD rather than dropped silently, so it still
 * shows up (with its reason) for anyone auditing signal history.
 *
 * If no confirmation timeframe is wired up (context.hasHigherTimeframeData()
 * is false - e.g. still warming up, or none configured), the raw signal
 * passes through unfiltered rather than being blocked indefinitely.
 */
public class TrendConfirmedEmaCrossoverStrategy implements TradingStrategy {

    private static final MathContext MC = MathContext.DECIMAL64;

    private final EmaCrossoverStrategy inner;
    private final int trendPeriod;

    public TrendConfirmedEmaCrossoverStrategy(int fastPeriod, int slowPeriod, int rsiPeriod,
                                              BigDecimal buyRsiThreshold, BigDecimal sellRsiThreshold,
                                              int trendPeriod) {
        this.inner = new EmaCrossoverStrategy(fastPeriod, slowPeriod, rsiPeriod, buyRsiThreshold, sellRsiThreshold);
        this.trendPeriod = trendPeriod;
    }

    @Override
    public StrategyDecision evaluate(MarketContext context) {
        StrategyDecision decision = inner.evaluate(context);
        if (decision == null || decision.signalType() == SignalType.HOLD) {
            return decision;
        }
        if (!context.hasHigherTimeframeData()) {
            return decision;
        }

        Optional<Boolean> higherTfBullish = higherTimeframeIsBullish(context.higherTimeframeCandles());
        if (higherTfBullish.isEmpty()) {
            return decision; // Not enough higher-timeframe history yet to judge trend
        }

        boolean agrees = (decision.signalType() == SignalType.BUY && higherTfBullish.get())
                || (decision.signalType() == SignalType.SELL && !higherTfBullish.get());
        if (agrees) {
            return decision;
        }

        return new StrategyDecision(SignalType.HOLD, decision.price(), 0.0,
                decision.reason() + "; downgraded to HOLD - contradicts " + context.higherTimeframe()
                        + " trend on " + context.symbol());
    }

    private Optional<Boolean> higherTimeframeIsBullish(List<Candle> higherTimeframeCandles) {
        if (higherTimeframeCandles.size() < trendPeriod) {
            return Optional.empty();
        }
        List<Candle> window = higherTimeframeCandles.subList(
                higherTimeframeCandles.size() - trendPeriod, higherTimeframeCandles.size());

        BigDecimal sum = BigDecimal.ZERO;
        for (Candle candle : window) {
            sum = sum.add(candle.getClose());
        }
        BigDecimal trendAverage = sum.divide(BigDecimal.valueOf(trendPeriod), MC);
        BigDecimal latestClose = window.get(window.size() - 1).getClose();

        return Optional.of(latestClose.compareTo(trendAverage) > 0);
    }
}
