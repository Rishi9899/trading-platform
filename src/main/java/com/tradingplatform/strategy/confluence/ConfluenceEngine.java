package com.tradingplatform.strategy.confluence;

import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.domain.strategy.StrategyInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregates signals from multiple strategies on the same symbol/timeframe/timestamp
 * and produces a confluence decision based on weighted vote agreement.
 *
 * The engine does NOT create new signals - it augments existing ones with consensus metadata.
 */
@Component
public class ConfluenceEngine {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceEngine.class);

    private final StrategyPerformanceWeighter performanceWeighter;
    private final VoteAggregator voteAggregator;

    public ConfluenceEngine(StrategyPerformanceWeighter performanceWeighter, VoteAggregator voteAggregator) {
        this.performanceWeighter = performanceWeighter;
        this.voteAggregator = voteAggregator;
    }

    /**
     * Augments a list of signals with confluence metadata.
     * Groups signals by symbol/timeframe/timestamp, calculates weighted agreement for each group,
     * and returns signals enhanced with confluence decisions.
     *
     * @param signals List of signals from a single candle close event
     * @return Signals augmented with confluence metadata
     */
    public List<AugmentedSignal> augmentSignals(List<Signal> signals) {
        if (signals == null || signals.isEmpty()) {
            return List.of();
        }

        // Group signals by symbol/timeframe/timestamp
        Map<String, List<Signal>> signalGroups = groupByKey(signals);

        List<AugmentedSignal> augmentedSignals = new ArrayList<>();

        for (Map.Entry<String, List<Signal>> entry : signalGroups.entrySet()) {
            String key = entry.getKey();
            List<Signal> groupSignals = entry.getValue();

            // Extract unique strategy instances from this group
            List<StrategyInstance> participatingStrategies = groupSignals.stream()
                    .map(Signal::getStrategyInstance)
                    .distinct()
                    .collect(Collectors.toList());

            // Calculate performance-based weights
            Map<Long, Double> weights = performanceWeighter.calculateWeights(participatingStrategies);

            // Aggregate votes
            ConfluenceDecision decision = voteAggregator.aggregateVotes(groupSignals, weights);

            log.debug("Confluence decision for {}: {} (score: {}, reason: {})",
                    key, decision.type(), decision.weightedAgreementScore(), decision.reason());

            // Augment each signal in the group with the decision
            for (Signal signal : groupSignals) {
                augmentedSignals.add(new AugmentedSignal(signal, decision));
            }
        }

        return augmentedSignals;
    }

    /**
     * Group signals by symbol + timeframe + timestamp
     */
    private Map<String, List<Signal>> groupByKey(List<Signal> signals) {
        Map<String, List<Signal>> groups = new HashMap<>();

        for (Signal signal : signals) {
            String key = signal.getSymbol() + "|" + signal.getTimeframe() + "|" + signal.getTimestamp();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(signal);
        }

        return groups;
    }

    /**
     * Signal wrapper that carries the original signal plus its confluence decision.
     */
    public record AugmentedSignal(Signal signal, ConfluenceDecision confluenceDecision) {
    }
}
