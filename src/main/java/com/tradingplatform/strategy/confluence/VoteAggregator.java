package com.tradingplatform.strategy.confluence;

import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.domain.signal.SignalType;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Counts votes from multiple strategy signals and calculates weighted agreement.
 * Each strategy's vote is weighted by its track record (performance metrics).
 */
public class VoteAggregator {

    /**
     * Aggregates votes from signals with weights calculated from strategy performance.
     *
     * @param signals List of signals from multiple strategies on same symbol/timeframe/timestamp
     * @param strategyWeights Map of strategy instance ID to weight (0.0 to 1.0)
     * @return ConfluenceDecision with vote outcome and agreement score
     */
    public ConfluenceDecision aggregateVotes(List<Signal> signals, Map<Long, Double> strategyWeights) {
        if (signals == null || signals.isEmpty()) {
            return createNoConsensusDecision("No signals to aggregate", strategyWeights);
        }

        int buyVotes = 0;
        int sellVotes = 0;
        double buyWeight = 0.0;
        double sellWeight = 0.0;

        for (Signal signal : signals) {
            if (signal.getSignalType() == SignalType.HOLD) {
                continue;
            }

            double weight = strategyWeights.getOrDefault(signal.getStrategyInstance().getId(), 1.0);

            if (signal.getSignalType() == SignalType.BUY) {
                buyVotes++;
                buyWeight += weight;
            } else if (signal.getSignalType() == SignalType.SELL) {
                sellVotes++;
                sellWeight += weight;
            }
        }

        // If no non-HOLD votes, return no consensus
        if (buyVotes + sellVotes == 0) {
            return createNoConsensusDecision("All signals are HOLD", strategyWeights);
        }

        double totalWeight = buyWeight + sellWeight;
        double weightedAgreementScore = Math.max(buyWeight, sellWeight) / totalWeight;

        ConfluenceDecision.DecisionType decisionType = ConfluenceDecision.determineType(weightedAgreementScore);

        String winningSignal = buyWeight > sellWeight ? "BUY" : (sellWeight > buyWeight ? "SELL" : "TIE");
        String reason = String.format(
                "Weighted vote: %s (%.2f). BUY: %d votes (%.2f weight), SELL: %d votes (%.2f weight)",
                winningSignal, weightedAgreementScore, buyVotes, buyWeight, sellVotes, sellWeight
        );

        return new ConfluenceDecision(
                decisionType,
                weightedAgreementScore,
                buyVotes,
                sellVotes,
                buyWeight,
                sellWeight,
                strategyWeights,
                reason
        );
    }

    /**
     * Creates a no-consensus decision when there's no clear vote or all HOLD.
     */
    private ConfluenceDecision createNoConsensusDecision(String reason, Map<Long, Double> strategyWeights) {
        return new ConfluenceDecision(
                ConfluenceDecision.DecisionType.NEUTRAL,
                0.5,
                0,
                0,
                0.0,
                0.0,
                strategyWeights,
                reason
        );
    }
}
