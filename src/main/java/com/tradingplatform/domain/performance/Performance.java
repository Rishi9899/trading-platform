package com.tradingplatform.domain.performance;

import com.tradingplatform.domain.strategy.StrategyInstance;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "performance",
        indexes = {
                @Index(
                        name = "idx_performance_strategy_instance_asof",
                        columnList = "strategy_instance_id,as_of"
                )
        }
)
public class Performance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "strategy_instance_id", nullable = false)
    private StrategyInstance strategyInstance;

    @Column(name = "as_of", nullable = false)
    private Instant asOf;

    @Column(name = "total_trades", nullable = false)
    private int totalTrades;

    @Column(name = "win_rate", precision = 5, scale = 4)
    private BigDecimal winRate;

    @Column(name = "net_pnl", precision = 18, scale = 4)
    private BigDecimal netPnl;

    @Column(name = "max_drawdown", precision = 18, scale = 4)
    private BigDecimal maxDrawdown;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Performance() {
    }

    public Performance(
            StrategyInstance strategyInstance,
            Instant asOf,
            int totalTrades,
            BigDecimal winRate,
            BigDecimal netPnl,
            BigDecimal maxDrawdown
    ) {
        this.strategyInstance = strategyInstance;
        this.asOf = asOf;
        this.totalTrades = totalTrades;
        this.winRate = winRate;
        this.netPnl = netPnl;
        this.maxDrawdown = maxDrawdown;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public StrategyInstance getStrategyInstance() {
        return strategyInstance;
    }

    public Instant getAsOf() {
        return asOf;
    }

    public int getTotalTrades() {
        return totalTrades;
    }

    public BigDecimal getWinRate() {
        return winRate;
    }

    public BigDecimal getNetPnl() {
        return netPnl;
    }

    public BigDecimal getMaxDrawdown() {
        return maxDrawdown;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}