package com.tradingplatform.evaluation;

import com.tradingplatform.domain.trade.TradeSide;

import java.math.BigDecimal;
import java.time.Instant;

public class SimulatedPosition {

    private final Long strategyInstanceId;
    private final String symbol;
    private final String timeframe;

    private final TradeSide side;
    private final BigDecimal entryPrice;
    private final Instant entryTime;

    public SimulatedPosition(
            Long strategyInstanceId,
            String symbol,
            String timeframe,
            TradeSide side,
            BigDecimal entryPrice,
            Instant entryTime
    ) {
        this.strategyInstanceId = strategyInstanceId;
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.side = side;
        this.entryPrice = entryPrice;
        this.entryTime = entryTime;
    }

    public Long getStrategyInstanceId() {
        return strategyInstanceId;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public TradeSide getSide() {
        return side;
    }

    public BigDecimal getEntryPrice() {
        return entryPrice;
    }

    public Instant getEntryTime() {
        return entryTime;
    }
}