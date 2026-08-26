package com.tradingplatform.strategy.impl;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.strategy.MarketContext;
import com.tradingplatform.strategy.StrategyDecision;
import com.tradingplatform.strategy.TradingStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendConfirmedEmaCrossoverStrategyTest {

    private static final BigDecimal DEFAULT_BUY_THRESHOLD = new BigDecimal("55");
    private static final BigDecimal DEFAULT_SELL_THRESHOLD = new BigDecimal("45");

    private static TradingStrategy defaultStrategy() {
        return new TrendConfirmedEmaCrossoverStrategy(3, 6, 3,
                DEFAULT_BUY_THRESHOLD, DEFAULT_SELL_THRESHOLD, 3);
    }

    @Test
    void passesThroughRawSignalWhenNoHigherTimeframeDataPresent() {
        // Same price path EmaCrossoverStrategyTest uses to reliably produce a BUY -
        // here with no higher-timeframe data attached at all.
        List<StrategyDecision> decisions = evaluateAll(defaultStrategy(), buildFlatThenUpPricePath(), List.of());

        assertTrue(countSignals(decisions, SignalType.BUY) > 0,
                "with no confirmation data the raw crossover signal should pass through unfiltered");
    }

    @Test
    void keepsBuySignalWhenHigherTimeframeAlsoTrendingUp() {
        List<Candle> bullishHigherTf = higherTimeframeCandles(100, 105, 110, 115, 120);

        List<StrategyDecision> decisions = evaluateAll(defaultStrategy(), buildFlatThenUpPricePath(), bullishHigherTf);

        assertTrue(countSignals(decisions, SignalType.BUY) > 0,
                "BUY should survive when the higher timeframe agrees with the direction");
    }

    @Test
    void downgradesBuySignalToHoldWhenHigherTimeframeIsTrendingDown() {
        List<Candle> bearishHigherTf = higherTimeframeCandles(120, 115, 110, 105, 100);

        List<StrategyDecision> decisions = evaluateAll(defaultStrategy(), buildFlatThenUpPricePath(), bearishHigherTf);

        assertEquals(0, countSignals(decisions, SignalType.BUY),
                "BUY should be downgraded to HOLD when it contradicts a downtrending higher timeframe");
    }

    private static MarketContext contextFor(Candle candle, List<Candle> higherTimeframeCandles) {
        if (higherTimeframeCandles.isEmpty()) {
            return new MarketContext(candle.getSymbol(), candle, List.of(candle));
        }
        return new MarketContext(candle.getSymbol(), candle, List.of(candle), "15m", higherTimeframeCandles);
    }

    private static Candle candle(String close) {
        Instant now = Instant.now();
        return new Candle("NIFTY", "5m", now, now.plusSeconds(300),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), 100L);
    }

    private static List<Candle> higherTimeframeCandles(int... closes) {
        List<Candle> candles = new ArrayList<>();
        Instant now = Instant.now();
        for (int close : closes) {
            BigDecimal price = BigDecimal.valueOf(close);
            candles.add(new Candle("NIFTY", "15m", now, now.plusSeconds(900), price, price, price, price, 100L));
        }
        return candles;
    }

    private static List<StrategyDecision> evaluateAll(TradingStrategy strategy, List<String> prices,
                                                        List<Candle> higherTimeframeCandles) {
        List<StrategyDecision> decisions = new ArrayList<>();
        for (String price : prices) {
            decisions.add(strategy.evaluate(contextFor(candle(price), higherTimeframeCandles)));
        }
        return decisions;
    }

    private static long countSignals(List<StrategyDecision> decisions, SignalType type) {
        return decisions.stream().filter(d -> d != null && d.signalType() == type).count();
    }

    private static List<String> buildFlatThenUpPricePath() {
        List<String> prices = new ArrayList<>();
        int price = 100;
        for (int i = 0; i < 10; i++) prices.add(String.valueOf(price));
        for (int i = 0; i < 15; i++) { price += 5; prices.add(String.valueOf(price)); }
        return prices;
    }
}
