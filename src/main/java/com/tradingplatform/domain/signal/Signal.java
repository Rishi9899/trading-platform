package com.tradingplatform.domain.signal;

import com.tradingplatform.domain.strategy.StrategyInstance;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "signal",
        indexes = {
                @Index(
                        name = "idx_signal_strategy_instance_time",
                        columnList = "strategy_instance_id,timestamp"
                )
        }
)
public class Signal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "strategy_instance_id", nullable = false)
    private StrategyInstance strategyInstance;

    @Column(nullable = false)
    private String symbol;

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

    protected Signal() {
    }

    public Signal(
            StrategyInstance strategyInstance,
            String symbol,
            Instant timestamp,
            SignalType signalType,
            BigDecimal price,
            Double confidence,
            String reason
    ) {
        this.strategyInstance = strategyInstance;
        this.symbol = symbol;
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
}