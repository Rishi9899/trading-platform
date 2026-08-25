package com.tradingplatform.evaluation;

import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.domain.trade.TradeSide;

import java.math.BigDecimal;

public class TradeSimulator {

    public BigDecimal calculatePnl(
            SimulatedPosition position,
            BigDecimal exitPrice
    ) {
        if (position == null) {
            throw new IllegalArgumentException(
                    "Position cannot be null"
            );
        }

        if (exitPrice == null) {
            throw new IllegalArgumentException(
                    "Exit price cannot be null"
            );
        }

        BigDecimal entryPrice = position.getEntryPrice();

        return switch (position.getSide()) {

            case BUY ->
                    exitPrice.subtract(entryPrice);

            case SELL ->
                    entryPrice.subtract(exitPrice);
        };
    }

    public SimulatedPosition openPosition(
            Long strategyInstanceId,
            Signal signal,
            TradeSide side
    ) {
        if (strategyInstanceId == null) {
            throw new IllegalArgumentException(
                    "Strategy instance ID cannot be null"
            );
        }

        if (signal == null) {
            throw new IllegalArgumentException(
                    "Signal cannot be null"
            );
        }

        if (side == null) {
            throw new IllegalArgumentException(
                    "Trade side cannot be null"
            );
        }

        return new SimulatedPosition(
                strategyInstanceId,
                signal.getSymbol(),
                signal.getTimeframe(),
                side,
                signal.getPrice(),
                signal.getTimestamp()
        );
    }
}