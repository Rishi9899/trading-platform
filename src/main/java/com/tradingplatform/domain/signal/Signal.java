package com.tradingplatform.domain.signal;

import com.tradingplatform.domain.strategy.StrategyInstance;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What a StrategyInstance said, at a point in time: "given this market
 * state, I think BUY/SELL/HOLD". A Signal is a strategy's opinion - it is
 * NOT an order and NOT a trade.
 *
 * Carries its own timeframe because symbol + timeframe is the strategy
 * isolation boundary throughout this platform (see StrategyEngine) - a
 * Signal without it would be ambiguous about which of potentially
 * several instances (same symbol, different timeframes) produced it.
 */
@Entity
@Table(name = "signal", indexes = {
        @Index(name = "idx_signal_strategy_instance_time", columnList = "strategy_instance_id,timestamp")
})
public class Signal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "strategy_instance_id", nullable = false)
    private StrategyInstance strategyInstance;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false, length = 16)
    private String timeframe;

    @Column(nullable = false)
    private Instant timestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, length = 16)
    private SignalType signalType;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal price;

    private Double confidence;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Confluence metadata (optional, populated by ConfluenceEngine)
    @Column(name = "confluence_decision_type", length = 16)
    private String confluenceDecisionType;

    @Column(name = "confluence_agreement_score", precision = 5, scale = 4)
    private BigDecimal confluenceAgreementScore;

    @Column(name = "confluence_vote_summary", length = 500)
    private String confluenceVoteSummary;

    protected Signal() {
        // required by JPA
    }

    public Signal(StrategyInstance strategyInstance, String symbol, String timeframe, Instant timestamp,
                  SignalType signalType, BigDecimal price, Double confidence, String reason) {
        this.strategyInstance = strategyInstance;
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.timestamp = timestamp;
        this.signalType = signalType;
        this.price = price;
        this.confidence = confidence;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public StrategyInstance getStrategyInstance() {
        return strategyInstance;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public SignalType getSignalType() {
        return signalType;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Double getConfidence() {
        return confidence;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getConfluenceDecisionType() {
        return confluenceDecisionType;
    }

    public BigDecimal getConfluenceAgreementScore() {
        return confluenceAgreementScore;
    }

    public String getConfluenceVoteSummary() {
        return confluenceVoteSummary;
    }

    /**
     * Apply confluence decision metadata to this signal (called by ConfluenceEngine).
     */
    public void applyConfluenceDecision(String decisionType, BigDecimal agreementScore, String voteSummary) {
        this.confluenceDecisionType = decisionType;
        this.confluenceAgreementScore = agreementScore;
        this.confluenceVoteSummary = voteSummary;
    }
}