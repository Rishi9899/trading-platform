package com.tradingplatform.evaluation;

import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.domain.strategy.Strategy;
import com.tradingplatform.domain.strategy.StrategyInstance;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class StrategyEvaluatorTest {

    @Test
    void buy100_buy105Ignored_sell110ClosesLongWithPnl10() {
        StrategyInstance instance = instanceWithId(1L);
        StrategyEvaluator evaluator = new StrategyEvaluator(1L);

        evaluator.onSignal(signal(instance, SignalType.BUY, "100"));
        assertNotNull(evaluator.getPosition());
        assertEquals(0, evaluator.getPosition().getEntryPrice().compareTo(new BigDecimal("100")));

        evaluator.onSignal(signal(instance, SignalType.BUY, "105")); // ignored - already LONG
        assertEquals(0, evaluator.getPosition().getEntryPrice().compareTo(new BigDecimal("100")),
                "repeated BUY while already LONG must not change the entry price");

        evaluator.onSignal(signal(instance, SignalType.SELL, "110")); // closes LONG
        assertNull(evaluator.getPosition(), "position should be closed after the opposite signal");
        assertEquals(1, evaluator.getPerformance().getTotalTrades());
        assertEquals(1, evaluator.getPerformance().getWinningTrades());
        assertEquals(0, evaluator.getPerformance().getNetProfit().compareTo(new BigDecimal("10")),
                "expected P&L of +10, got " + evaluator.getPerformance().getNetProfit());
    }

    @Test
    void sell100_sell95Ignored_buy90ClosesShortWithPnl10() {
        StrategyInstance instance = instanceWithId(2L);
        StrategyEvaluator evaluator = new StrategyEvaluator(2L);

        evaluator.onSignal(signal(instance, SignalType.SELL, "100"));
        assertNotNull(evaluator.getPosition());
        assertEquals(0, evaluator.getPosition().getEntryPrice().compareTo(new BigDecimal("100")));

        evaluator.onSignal(signal(instance, SignalType.SELL, "95")); // ignored - already SHORT
        assertEquals(0, evaluator.getPosition().getEntryPrice().compareTo(new BigDecimal("100")),
                "repeated SELL while already SHORT must not change the entry price");

        evaluator.onSignal(signal(instance, SignalType.BUY, "90")); // closes SHORT
        assertNull(evaluator.getPosition());
        assertEquals(1, evaluator.getPerformance().getTotalTrades());
        assertEquals(1, evaluator.getPerformance().getWinningTrades());
        assertEquals(0, evaluator.getPerformance().getNetProfit().compareTo(new BigDecimal("10")),
                "expected P&L of +10, got " + evaluator.getPerformance().getNetProfit());
    }

    @Test
    void signalsForADifferentStrategyInstanceAreIgnored() {
        StrategyInstance other = instanceWithId(99L);
        StrategyEvaluator evaluator = new StrategyEvaluator(1L);

        evaluator.onSignal(signal(other, SignalType.BUY, "100"));

        assertNull(evaluator.getPosition(), "signals for a different strategy instance must be ignored");
    }

    @Test
    void losingTradeReducesNetProfitAndCountsAsLosingTrade() {
        StrategyInstance instance = instanceWithId(3L);
        StrategyEvaluator evaluator = new StrategyEvaluator(3L);

        evaluator.onSignal(signal(instance, SignalType.BUY, "100"));
        evaluator.onSignal(signal(instance, SignalType.SELL, "90")); // loss of 10

        assertEquals(1, evaluator.getPerformance().getLosingTrades());
        assertEquals(0, evaluator.getPerformance().getWinningTrades());
        assertEquals(0, evaluator.getPerformance().getNetProfit().compareTo(new BigDecimal("-10")));
    }

    @Test
    void holdSignalsDoNotAffectAnOpenPosition() {
        StrategyInstance instance = instanceWithId(4L);
        StrategyEvaluator evaluator = new StrategyEvaluator(4L);

        evaluator.onSignal(signal(instance, SignalType.BUY, "100"));
        evaluator.onSignal(signal(instance, SignalType.HOLD, "103"));
        evaluator.onSignal(signal(instance, SignalType.HOLD, "107"));

        assertNotNull(evaluator.getPosition(), "HOLD must not close or alter an open position");
        assertEquals(0, evaluator.getPosition().getEntryPrice().compareTo(new BigDecimal("100")));
        assertEquals(0, evaluator.getPerformance().getTotalTrades(),
                "no trade should be recorded while the position is still open");
    }

    /**
     * StrategyInstance's id is normally JPA-generated on save, so a
     * plain `new StrategyInstance(...)` has a null id - not usable here
     * since StrategyEvaluator requires a non-null id up front. Setting
     * it via reflection avoids persisting to a real database just to
     * get a test fixture with a known id.
     */
    private static StrategyInstance instanceWithId(Long id) {
        Strategy strategy = new Strategy("test-strategy", "test");
        StrategyInstance instance = new StrategyInstance(strategy, "NIFTY", "10s", "{}");
        try {
            Field idField = StrategyInstance.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(instance, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return instance;
    }

    private static Signal signal(StrategyInstance instance, SignalType type, String price) {
        return new Signal(instance, instance.getSymbol(), instance.getTimeframe(),
                Instant.now(), type, new BigDecimal(price), null, "test");
    }
}