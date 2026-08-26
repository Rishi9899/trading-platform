package com.tradingplatform.strategy.confluence;

import com.tradingplatform.domain.signal.SignalType;

import java.util.Map;

/**
 * Represents the aggregated vote outcome from multiple strategies on the same
 * symbol/timeframe at the same moment. Carries the decision type (BOOST/NEUTRAL/VETO),
 * a weighted agreement score, and metadata about which strategies participated.
 *
 * This is not itself a signal - it augments existing signals with multi-strategy consensus data.
 */
public record ConfluenceDecision(
        DecisionType type,
        double weightedAgreementScore,
        int buyVotes,
        int sellVotes,
        double buyWeight,
        double sellWeight,
        Map<Long, Double> strategyWeights,
        String reason
) {

    public enum DecisionType {
        /**
         * Strong agreement (weighted score > 0.65): boost the winning side's confidence,
         * consider vetoing/lowering confidence for minority opinion
         */
        BOOST,

        /**
         * Unclear or no clear agreement (0.50 <= score <= 0.65): keep original confidence,
         * don't adjust based on weak consensus
         */
        NEUTRAL,

        /**
         * Weak dissent (weighted score < 0.50): minority has stronger voice,
         * consider lowering winning side's confidence or skipping trade entirely
         */
        VETO
    }

    /**
     * Determines decision type based on weighted agreement score.
     * - > 0.65: BOOST (strong agreement)
     * - 0.50 to 0.65: NEUTRAL (weak agreement)
     * - < 0.50: VETO (dissent)
     */
    public static DecisionType determineType(double score) {
        if (score > 0.65) {
            return DecisionType.BOOST;
        } else if (score < 0.50) {
            return DecisionType.VETO;
        } else {
            return DecisionType.NEUTRAL;
        }
    }

    /**
     * Get the consensus signal type (BUY or SELL based on vote weights)
     */
    public SignalType getConsensusSignalType() {
        if (buyWeight > sellWeight) {
            return SignalType.BUY;
        } else if (sellWeight > buyWeight) {
            return SignalType.SELL;
        } else {
            return SignalType.HOLD;
        }
    }
}
