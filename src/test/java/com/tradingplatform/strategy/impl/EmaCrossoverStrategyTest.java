package com.tradingplatform.strategy.impl;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.strategy.MarketContext;
import com.tradingplatform.strategy.StrategyDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmaCrossoverStrategyTest {

    private static final BigDecimal DEFAULT_BUY_THRESHOLD = new BigDecimal("55");
    private static final BigDecimal DEFAULT_SELL_THRESHOLD = new BigDecimal("45");

    private static EmaCrossoverStrategy defaultStrategy() {
        return new EmaCrossoverStrategy(3, 6, 3, DEFAULT_BUY_THRESHOLD, DEFAULT_SELL_THRESHOLD);
    }

    @Test
    void noSignalDuringWarmUp() {
        EmaCrossoverStrategy strategy = defaultStrategy();
        for (int i = 0; i < 5; i++) {
            StrategyDecision decision = strategy.evaluate(contextFor(candle("100")));
            assertNull(decision, "must not produce a decision before all indicators are ready");
        }
    }

    @Test
    void initialIndicatorReadinessDoesNotCreateAFalseCrossover() {
        EmaCrossoverStrategy strategy = defaultStrategy();
        StrategyDecision firstReadyDecision = null;
        for (int i = 0; i < 6; i++) {
            firstReadyDecision = strategy.evaluate(contextFor(candle("100")));
        }
        assertNull(firstReadyDecision,
                "the candle where indicators first become ready must not itself count as a crossover");
    }

    @Test
    void detectsCrossoversWithoutRepeatingTheSameSignalEveryCandle() {
        EmaCrossoverStrategy strategy = defaultStrategy();
        List<StrategyDecision> decisions = evaluateAll(strategy, buildFlatUpDownUpPricePath());

        long buyCount = countSignals(decisions, SignalType.BUY);
        long sellCount = countSignals(decisions, SignalType.SELL);

        assertTrue(buyCount >= 1 && buyCount <= 3,
                "expected a small number of BUY signals (one per real crossover), got " + buyCount);
        assertTrue(sellCount >= 1 && sellCount <= 3,
                "expected a small number of SELL signals (one per real crossover), got " + sellCount);

        assertNoImmediatelyRepeatedSignal(decisions, SignalType.BUY);
        assertNoImmediatelyRepeatedSignal(decisions, SignalType.SELL);
    }

    @Test
    void bullishCrossoverWithRsiBelowBuyThresholdProducesHoldNotBuy() {
        // Threshold set to exactly 100: a sustained all-gains price run
        // makes RSI read exactly 100.00 (RelativeStrengthIndex short-
        // circuits to the HUNDRED constant when avgLoss is zero), so this
        // is a precise boundary test - it also proves the comparison is
        // strict ">" and not ">=", not just "some high number".
        EmaCrossoverStrategy strategy = new EmaCrossoverStrategy(3, 6, 3,
                new BigDecimal("100"), DEFAULT_SELL_THRESHOLD);

        List<StrategyDecision> decisions = evaluateAll(strategy, buildFlatThenUpPricePath());

        assertEquals(0, countSignals(decisions, SignalType.BUY),
                "RSI gate should block BUY even though a bullish crossover geometrically occurs");
        assertTrue(countSignals(decisions, SignalType.HOLD) > 0,
                "a blocked crossover should still produce HOLD decisions, not silence");
    }

    @Test
    void bearishCrossoverWithRsiAboveSellThresholdProducesHoldNotSell() {
        EmaCrossoverStrategy strategy = new EmaCrossoverStrategy(3, 6, 3,
                DEFAULT_BUY_THRESHOLD, new BigDecimal("0.1"));

        List<StrategyDecision> decisions = evaluateAll(strategy, buildFlatUpThenDownPricePath());

        assertEquals(0, countSignals(decisions, SignalType.SELL),
                "RSI gate should block SELL even though a bearish crossover geometrically occurs");
    }

    @Test
    void stayingAboveSlowEmaAfterCrossoverProducesHoldNotRepeatedBuy() {
        EmaCrossoverStrategy strategy = defaultStrategy();
        List<StrategyDecision> decisions = evaluateAll(strategy, buildFlatThenUpPricePath());

        int firstBuyIndex = indexOfFirst(decisions, SignalType.BUY);
        assertTrue(firstBuyIndex >= 0, "expected at least one BUY in a sustained uptrend");

        for (int i = firstBuyIndex + 1; i < decisions.size(); i++) {
            StrategyDecision d = decisions.get(i);
            if (d != null) {
                assertNotEquals(SignalType.BUY, d.signalType(),
                        "must not repeat BUY at index " + i + " while the EMA relationship is unchanged");
            }
        }
    }

    private static MarketContext contextFor(Candle candle) {
        return new MarketContext(candle.getSymbol(), candle, List.of(candle));
    }

    private static Candle candle(String close) {
        Instant now = Instant.now();
        return new Candle("NIFTY", "1m", now, now.plusSeconds(60),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), 100L);
    }

    private static List<StrategyDecision> evaluateAll(EmaCrossoverStrategy strategy, List<String> prices) {
        List<StrategyDecision> decisions = new ArrayList<>();
        for (String price : prices) {
            decisions.add(strategy.evaluate(contextFor(candle(price))));
        }
        return decisions;
    }

    private static long countSignals(List<StrategyDecision> decisions, SignalType type) {
        return decisions.stream().filter(d -> d != null && d.signalType() == type).count();
    }

    private static int indexOfFirst(List<StrategyDecision> decisions, SignalType type) {
        for (int i = 0; i < decisions.size(); i++) {
            StrategyDecision d = decisions.get(i);
            if (d != null && d.signalType() == type) {
                return i;
            }
        }
        return -1;
    }

    private static void assertNoImmediatelyRepeatedSignal(List<StrategyDecision> decisions, SignalType type) {
        boolean previousWasSignal = false;
        for (StrategyDecision d : decisions) {
            boolean isSignal = d != null && d.signalType() == type;
            if (isSignal && previousWasSignal) {
                fail("Same-candle-adjacent repeat of " + type + " detected - crossover must be edge-triggered");
            }
            if (d != null) {
                previousWasSignal = isSignal;
            }
        }
    }

    private static List<String> buildFlatUpDownUpPricePath() {
        List<String> prices = new ArrayList<>();
        int price = 100;
        for (int i = 0; i < 10; i++) prices.add(String.valueOf(price));
        for (int i = 0; i < 10; i++) { price += 5; prices.add(String.valueOf(price)); }
        for (int i = 0; i < 10; i++) { price -= 5; prices.add(String.valueOf(price)); }
        for (int i = 0; i < 10; i++) { price += 5; prices.add(String.valueOf(price)); }
        return prices;
    }

    private static List<String> buildFlatThenUpPricePath() {
        List<String> prices = new ArrayList<>();
        int price = 100;
        for (int i = 0; i < 10; i++) prices.add(String.valueOf(price));
        for (int i = 0; i < 15; i++) { price += 5; prices.add(String.valueOf(price)); }
        return prices;
    }

    private static List<String> buildFlatUpThenDownPricePath() {
        List<String> prices = new ArrayList<>();
        int price = 100;
        for (int i = 0; i < 10; i++) prices.add(String.valueOf(price));
        for (int i = 0; i < 8; i++) { price += 5; prices.add(String.valueOf(price)); }
        for (int i = 0; i < 15; i++) { price -= 5; prices.add(String.valueOf(price)); }
        return prices;
    }
}