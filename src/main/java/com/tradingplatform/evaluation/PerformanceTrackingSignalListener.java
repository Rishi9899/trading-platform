package com.tradingplatform.evaluation;

import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.strategy.SignalListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes signals to StrategyEvaluators for paper trading.
 * Only executes paper trades for signals that pass the confluence filter.
 */
@Component
public class PerformanceTrackingSignalListener implements SignalListener {

    private static final Logger log = LoggerFactory.getLogger(PerformanceTrackingSignalListener.class);

    private static final Set<String> TRADEABLE_CONFLUENCE_TYPES = Set.of(
            "STRONG_AGREEMENT", "UNANIMOUS"
    );

    private final Map<Long, StrategyEvaluator> evaluators = new ConcurrentHashMap<>();

    @Override
    public void onSignal(Signal signal) {
        if (signal == null || signal.getStrategyInstance() == null) {
            return;
        }

        // Skip HOLD signals — they don't affect positions
        if (signal.getSignalType() == SignalType.HOLD) {
            return;
        }

        String confluenceType = signal.getConfluenceDecisionType();

        // If no confluence metadata (single-voter symbol), always trade
        if (confluenceType == null) {
            executePaperTrade(signal);
            return;
        }

        // Only paper trade STRONG/UNANIMOUS signals
        if (TRADEABLE_CONFLUENCE_TYPES.contains(confluenceType)) {
            log.info("[PAPER TRADE] Executing {} for {} | Confluence: {} | Score: {}",
                    signal.getSignalType(), signal.getSymbol(),
                    confluenceType, signal.getConfluenceAgreementScore());
            executePaperTrade(signal);
        } else {
            log.debug("[PAPER SKIP] Skipping {} for {} | Confluence: {} (not strong enough)",
                    signal.getSignalType(), signal.getSymbol(), confluenceType);
        }
    }

    private void executePaperTrade(Signal signal) {
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