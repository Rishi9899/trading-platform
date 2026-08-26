package com.tradingplatform.strategy.confluence;

import com.tradingplatform.domain.performance.Performance;
import com.tradingplatform.domain.performance.PerformanceRepository;
import com.tradingplatform.domain.strategy.StrategyInstance;
import com.tradingplatform.evaluation.StrategyPerformance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calculates vote weights for strategy instances based on their performance track record.
 * Weight is derived from recent profitability metrics (win rate, profit factor, net P&L).
 */
@Component
public class StrategyPerformanceWeighter {

    private static final Logger log = LoggerFactory.getLogger(StrategyPerformanceWeighter.class);
    private static final double DEFAULT_WEIGHT = 1.0;
    private static final double MIN_WEIGHT = 0.1;
    private static final double MAX_WEIGHT = 2.0;

    private final PerformanceRepository performanceRepository;

    public StrategyPerformanceWeighter(PerformanceRepository performanceRepository) {
        this.performanceRepository = performanceRepository;
    }

    /**
     * Calculate normalized weights for a list of strategy instances.
     * Weights are based on profitability metrics; negative performers get lower weight.
     *
     * @param strategyInstances List of strategy instances to weight
     * @return Map of strategy instance ID to normalized weight (sums to strategyInstances.size())
     */
    public Map<Long, Double> calculateWeights(List<StrategyInstance> strategyInstances) {
        Map<Long, Double> rawWeights = new HashMap<>();

        for (StrategyInstance instance : strategyInstances) {
            double weight = calculateSingleWeight(instance);
            rawWeights.put(instance.getId(), weight);
        }

        // Normalize so weights sum to count of strategies
        return normalizeWeights(rawWeights, strategyInstances.size());
    }

    /**
     * Calculate a single strategy's weight based on its performance.
     */
    private double calculateSingleWeight(StrategyInstance instance) {
        try {
            // Query most recent performance for this strategy instance
            var performances = performanceRepository.findAll().stream()
                    .filter(p -> p.getStrategyInstance().getId().equals(instance.getId()))
                    .toList();

            if (performances.isEmpty()) {
                return DEFAULT_WEIGHT;
            }

            // Get most recent performance
            Performance performance = performances.stream()
                    .max((a, b) -> a.getAsOf().compareTo(b.getAsOf()))
                    .orElse(null);

            if (performance == null) {
                return DEFAULT_WEIGHT;
            }

            // Use composite metric: (win rate * 0.5) + (net P&L sentiment * 0.5)
            BigDecimal winRateBD = performance.getWinRate();
            BigDecimal netPnl = performance.getNetPnl();

            if (winRateBD == null || netPnl == null) {
                return DEFAULT_WEIGHT;
            }

            double winRate = Math.min(winRateBD.doubleValue(), 1.0); // Cap at 1.0 (100%)
            double pnlScore = netPnl.doubleValue() > 0 ? 1.0 : 0.0; // Simple: profitable or not

            double compositeScore = (winRate * 0.5) + (pnlScore * 0.5);
            double weight = DEFAULT_WEIGHT + (compositeScore * (MAX_WEIGHT - DEFAULT_WEIGHT));

            return Math.max(Math.min(weight, MAX_WEIGHT), MIN_WEIGHT);
        } catch (Exception e) {
            log.warn("Failed to calculate weight for strategy {}: {}", instance.getId(), e.getMessage());
            return DEFAULT_WEIGHT;
        }
    }

    /**
     * Normalize raw weights so they sum to the strategy count (equal representation by default).
     */
    private Map<Long, Double> normalizeWeights(Map<Long, Double> rawWeights, int strategyCount) {
        double totalWeight = rawWeights.values().stream().mapToDouble(Double::doubleValue).sum();

        if (totalWeight <= 0) {
            // All weights invalid, revert to equal weights
            Map<Long, Double> equalWeights = new HashMap<>();
            double equalWeight = (double) strategyCount / rawWeights.size();
            rawWeights.keySet().forEach(id -> equalWeights.put(id, equalWeight));
            return equalWeights;
        }

        Map<Long, Double> normalized = new HashMap<>();
        double targetSum = strategyCount;
        double normalizationFactor = targetSum / totalWeight;

        rawWeights.forEach((id, rawWeight) ->
                normalized.put(id, rawWeight * normalizationFactor)
        );

        return normalized;
    }
}
