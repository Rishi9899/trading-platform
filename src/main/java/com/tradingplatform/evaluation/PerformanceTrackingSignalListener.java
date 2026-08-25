package com.tradingplatform.evaluation;

import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.strategy.SignalListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single SignalListener registered on StrategyEngine, routing each
 * incoming signal directly to the right StrategyEvaluator by strategy
 * instance ID (O(1) lookup) instead of broadcasting to every evaluator
 * and having each check "is this mine?". Evaluators are created lazily
 * on first signal - no manual per-instance wiring needed as YAML-loaded
 * strategy instances come and go.
 */
public class PerformanceTrackingSignalListener implements SignalListener {

    private final Map<Long, StrategyEvaluator> evaluators = new ConcurrentHashMap<>();

    @Override
    public void onSignal(Signal signal) {
        if (signal == null || signal.getStrategyInstance() == null) {
            return;
        }
        Long id = signal.getStrategyInstance().getId();
        evaluators.computeIfAbsent(id, StrategyEvaluator::new).onSignal(signal);
    }

    public StrategyPerformance getPerformance(Long strategyInstanceId) {
        StrategyEvaluator evaluator = evaluators.get(strategyInstanceId);
        return evaluator != null ? evaluator.getPerformance() : null;
    }

    public Map<Long, StrategyEvaluator> getEvaluators() {
        return Map.copyOf(evaluators);
    }
}