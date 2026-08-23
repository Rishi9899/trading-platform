package com.tradingplatform.domain.strategy;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "strategy_instance")
public class StrategyInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "strategy_id", nullable = false)
    private Strategy strategy;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String timeframe;

    @Column(columnDefinition = "TEXT")
    private String parameters;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StrategyInstanceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StrategyInstance() {
    }

    public StrategyInstance(
            Strategy strategy,
            String symbol,
            String timeframe,
            String parameters
    ) {
        this.strategy = strategy;
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.parameters = parameters;
        this.status = StrategyInstanceStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public String getParameters() {
        return parameters;
    }

    public StrategyInstanceStatus getStatus() {
        return status;
    }

    public void setStatus(StrategyInstanceStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}