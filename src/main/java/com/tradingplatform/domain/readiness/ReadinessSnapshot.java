package com.tradingplatform.domain.readiness;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot of strategy readiness for a symbol/timeframe
 */
public class ReadinessSnapshot {

    private final String symbol;
    private final String timeframe;
    private final int readinessPercent;
    private final int currentVotes;
    private final int requiredVotes;
    private final String signal;
    private final double agreementScore;
    private final List<String> blockers;
    private final String nearestTrigger;
    private final Instant updatedAt;

    public ReadinessSnapshot(String symbol, String timeframe, int readinessPercent,
                             int currentVotes, int requiredVotes, String signal,
                             double agreementScore, List<String> blockers,
                             String nearestTrigger, Instant updatedAt) {
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.readinessPercent = readinessPercent;
        this.currentVotes = currentVotes;
        this.requiredVotes = requiredVotes;
        this.signal = signal;
        this.agreementScore = agreementScore;
        this.blockers = blockers;
        this.nearestTrigger = nearestTrigger;
        this.updatedAt = updatedAt;
    }

    // Getters
    public String getSymbol() { return symbol; }
    public String getTimeframe() { return timeframe; }
    public int getReadinessPercent() { return readinessPercent; }
    public int getCurrentVotes() { return currentVotes; }
    public int getRequiredVotes() { return requiredVotes; }
    public String getSignal() { return signal; }
    public double getAgreementScore() { return agreementScore; }
    public List<String> getBlockers() { return blockers; }
    public String getNearestTrigger() { return nearestTrigger; }
    public Instant getUpdatedAt() { return updatedAt; }
}