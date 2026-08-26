package com.tradingplatform.strategy.confluence;

import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.domain.strategy.StrategyInstance;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VoteAggregatorTest {

    private final VoteAggregator voteAggregator = new VoteAggregator();

    @Test
    void testSimpleMajority_2of3BUY() {
        List<Signal> signals = List.of(
                createSignal(1L, SignalType.BUY),
                createSignal(2L, SignalType.BUY),
                createSignal(3L, SignalType.SELL)
        );
        
        Map<Long, Double> weights = Map.of(1L, 1.0, 2L, 1.0, 3L, 1.0);

        ConfluenceDecision decision = voteAggregator.aggregateVotes(signals, weights);

        assertEquals(ConfluenceDecision.DecisionType.BOOST, decision.type());
        assertEquals(2, decision.buyVotes());
        assertEquals(1, decision.sellVotes());
        assertTrue(decision.weightedAgreementScore() > 0.65);
    }

    @Test
    void testWeightedVote_StrongStrategyOutweighs2Weak() {
        List<Signal> signals = List.of(
                createSignal(1L, SignalType.BUY),
                createSignal(2L, SignalType.SELL),
                createSignal(3L, SignalType.SELL)
        );
        
        Map<Long, Double> weights = Map.of(1L, 2.0, 2L, 0.5, 3L, 0.5);

        ConfluenceDecision decision = voteAggregator.aggregateVotes(signals, weights);

        assertEquals(SignalType.BUY, decision.getConsensusSignalType());
        assertTrue(decision.buyWeight() > decision.sellWeight());
    }

    @Test
    void testAllHoldSignals() {
        List<Signal> signals = List.of(
                createSignal(1L, SignalType.HOLD),
                createSignal(2L, SignalType.HOLD),
                createSignal(3L, SignalType.HOLD)
        );
        
        Map<Long, Double> weights = Map.of(1L, 1.0, 2L, 1.0, 3L, 1.0);

        ConfluenceDecision decision = voteAggregator.aggregateVotes(signals, weights);

        assertEquals(ConfluenceDecision.DecisionType.NEUTRAL, decision.type());
        assertEquals(0, decision.buyVotes());
        assertEquals(0, decision.sellVotes());
    }

    @Test
    void testTiedVote_2BUYvs2SELL() {
        List<Signal> signals = List.of(
                createSignal(1L, SignalType.BUY),
                createSignal(2L, SignalType.BUY),
                createSignal(3L, SignalType.SELL),
                createSignal(4L, SignalType.SELL)
        );
        
        Map<Long, Double> weights = Map.of(1L, 1.0, 2L, 1.0, 3L, 1.0, 4L, 1.0);

        ConfluenceDecision decision = voteAggregator.aggregateVotes(signals, weights);

        assertEquals(0.5, decision.weightedAgreementScore());
        assertEquals(ConfluenceDecision.DecisionType.NEUTRAL, decision.type());
    }

    @Test
    void testSingleBUYVote() {
        List<Signal> signals = List.of(
                createSignal(1L, SignalType.BUY),
                createSignal(2L, SignalType.HOLD),
                createSignal(3L, SignalType.HOLD)
        );
        
        Map<Long, Double> weights = Map.of(1L, 1.0, 2L, 1.0, 3L, 1.0);

        ConfluenceDecision decision = voteAggregator.aggregateVotes(signals, weights);

        assertEquals(1, decision.buyVotes());
        assertEquals(0, decision.sellVotes());
        assertEquals(1.0, decision.weightedAgreementScore());
        assertEquals(ConfluenceDecision.DecisionType.BOOST, decision.type());
    }

    @Test
    void testEmptySignalList() {
        ConfluenceDecision decision = voteAggregator.aggregateVotes(List.of(), Map.of());

        assertEquals(ConfluenceDecision.DecisionType.NEUTRAL, decision.type());
        assertEquals(0.5, decision.weightedAgreementScore());
    }

    @Test
    void testNullSignalList() {
        ConfluenceDecision decision = voteAggregator.aggregateVotes(null, Map.of());

        assertEquals(ConfluenceDecision.DecisionType.NEUTRAL, decision.type());
        assertEquals(0.5, decision.weightedAgreementScore());
    }

    private Signal createSignal(Long strategyId, SignalType signalType) {
        StrategyInstance instance = Mockito.mock(StrategyInstance.class);
        Mockito.when(instance.getId()).thenReturn(strategyId);

        return new Signal(
                instance,
                "AAPL",
                "1m",
                Instant.now(),
                signalType,
                BigDecimal.valueOf(150.25),
                0.75,
                "Test signal"
        );
    }
}