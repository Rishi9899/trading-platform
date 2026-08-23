package com.tradingplatform.domain.trade;

import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.domain.strategy.StrategyInstance;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "trade",
        indexes = {
                @Index(
                        name = "idx_trade_strategy_instance_time",
                        columnList = "strategy_instance_id,executed_at"
                )
        }
)
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "strategy_instance_id", nullable = false)
    private StrategyInstance strategyInstance;

    @ManyToOne
    @JoinColumn(name = "signal_id")
    private Signal signal;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TradeSide side;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExecutionMode mode;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Trade() {
    }

    public Trade(
            StrategyInstance strategyInstance,
            Signal signal,
            String symbol,
            TradeSide side,
            BigDecimal quantity,
            BigDecimal price,
            ExecutionMode mode,
            Instant executedAt
    ) {
        this.strategyInstance = strategyInstance;
        this.signal = signal;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.mode = mode;
        this.executedAt = executedAt;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public StrategyInstance getStrategyInstance() {
        return strategyInstance;
    }

    public Signal getSignal() {
        return signal;
    }

    public String getSymbol() {
        return symbol;
    }

    public TradeSide getSide() {
        return side;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public ExecutionMode getMode() {
        return mode;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}