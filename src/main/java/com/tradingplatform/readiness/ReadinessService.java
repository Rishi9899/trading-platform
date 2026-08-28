package com.tradingplatform.readiness;

import com.tradingplatform.domain.readiness.ReadinessSnapshot;
import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.strategy.confluence.ConfluenceDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks signal readiness - how close we are to executing a trade
 */
@Service
public class ReadinessService {

    private static final Logger log = LoggerFactory.getLogger(ReadinessService.class);
    private static final int VOTE_THRESHOLD = 3;

    private final Map<String, ReadinessSnapshot> snapshots = new ConcurrentHashMap<>();
    private final RiskGateService riskGateService;

    public ReadinessService(RiskGateService riskGateService) {
        this.riskGateService = riskGateService;
    }

    /**
     * Update readiness after strategy evaluation
     */
    public ReadinessSnapshot updateReadiness(String symbol, String timeframe,
                                             List<Signal> allSignals, ConfluenceDecision confluenceDecision) {
        String key = symbol + "|" + timeframe;

        // Count votes
        long buyVotes = allSignals.stream().filter(s -> s.getSignalType() == SignalType.BUY).count();
        long sellVotes = allSignals.stream().filter(s -> s.getSignalType() == SignalType.SELL).count();
        int currentVotes = (int) Math.max(buyVotes, sellVotes);

        // Determine signal from confluence decision
        String signal = "HOLD";
        if (confluenceDecision != null) {
            signal = confluenceDecision.type().name();
        } else if (currentVotes >= VOTE_THRESHOLD) {
            signal = buyVotes > sellVotes ? "BUY" : "SELL";
        }

        // Calculate readiness percentage
        int readinessPercent = Math.min(100, (currentVotes * 100) / VOTE_THRESHOLD);

        // Calculate agreement score from confluence decision
        double agreementScore = 0.5; // neutral
        if (confluenceDecision != null) {
            agreementScore = confluenceDecision.weightedAgreementScore();
        } else if (allSignals.size() > 0) {
            agreementScore = (double) currentVotes / allSignals.size();
        }

        // Check blockers
        List<String> blockers = riskGateService.checkBlockers(symbol, timeframe);

        // Find nearest trigger
        String nearestTrigger = findNearestTrigger(allSignals, confluenceDecision);

        ReadinessSnapshot snapshot = new ReadinessSnapshot(
                symbol,
                timeframe,
                readinessPercent,
                currentVotes,
                VOTE_THRESHOLD,
                signal,
                agreementScore,
                blockers,
                nearestTrigger,
                Instant.now()
        );

        snapshots.put(key, snapshot);

        log.debug("Readiness updated for {}: {}% ready, {} votes, signal={}, agreement={}",
                key, readinessPercent, currentVotes, signal, String.format("%.2f", agreementScore));

        return snapshot;
    }

    /**
     * Get readiness snapshot for a symbol/timeframe
     */
    public Optional<ReadinessSnapshot> getReadiness(String symbol, String timeframe) {
        String key = symbol + "|" + timeframe;
        return Optional.ofNullable(snapshots.get(key));
    }

    /**
     * Get all readiness snapshots
     */
    public Map<String, ReadinessSnapshot> getAllReadiness() {
        return new HashMap<>(snapshots);
    }

    private String findNearestTrigger(List<Signal> signals, ConfluenceDecision confluenceDecision) {
        // Prefer confluence decision reason if available
        if (confluenceDecision != null && confluenceDecision.reason() != null && !confluenceDecision.reason().isEmpty()) {
            return confluenceDecision.reason();
        }

        // Fall back to most recent signal reason
        if (signals.isEmpty()) {
            return "No signals yet";
        }

        return signals.stream()
                .filter(s -> s.getReason() != null && !s.getReason().isEmpty())
                .findFirst()
                .map(Signal::getReason)
                .orElse("Waiting for conditions");
    }
}