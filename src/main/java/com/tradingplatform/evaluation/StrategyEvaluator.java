package com.tradingplatform.evaluation;

import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.domain.trade.TradeSide;
import com.tradingplatform.strategy.SignalListener;

public class StrategyEvaluator implements SignalListener {

    private final Long strategyInstanceId;
    private final TradeSimulator tradeSimulator;
    private final StrategyPerformance performance;

    private SimulatedPosition position;

    public StrategyEvaluator(Long strategyInstanceId) {
        if (strategyInstanceId == null) {
            throw new IllegalArgumentException(
                    "Strategy instance ID cannot be null"
            );
        }

        this.strategyInstanceId = strategyInstanceId;
        this.tradeSimulator = new TradeSimulator();
        this.performance = new StrategyPerformance(strategyInstanceId);
    }

    @Override
    public void onSignal(Signal signal) {

        if (signal == null) {
            return;
        }

        if (!strategyInstanceId.equals(
                signal.getStrategyInstance().getId())) {
            return;
        }

        SignalType signalType = signal.getSignalType();

        if (signalType == null) {
            return;
        }

        switch (signalType) {

            case BUY -> handleBuy(signal);

            case SELL -> handleSell(signal);

            case HOLD -> {
                // HOLD does not change the simulated position.
            }
        }
    }

    private void handleBuy(Signal signal) {

        if (position == null) {

            position = tradeSimulator.openPosition(
                    strategyInstanceId,
                    signal,
                    TradeSide.BUY
            );

            return;
        }

        if (position.getSide() == TradeSide.SELL) {

            closePosition(signal);

            position = null;
        }

        // If already BUY, ignore repeated BUY.
    }

    private void handleSell(Signal signal) {

        if (position == null) {

            position = tradeSimulator.openPosition(
                    strategyInstanceId,
                    signal,
                    TradeSide.SELL
            );

            return;
        }

        if (position.getSide() == TradeSide.BUY) {

            closePosition(signal);

            position = null;
        }

        // If already SELL, ignore repeated SELL.
    }

    private void closePosition(Signal signal) {

        var pnl = tradeSimulator.calculatePnl(
                position,
                signal.getPrice()
        );

        performance.recordTrade(pnl);
    }

    public StrategyPerformance getPerformance() {
        return performance;
    }

    public SimulatedPosition getPosition() {
        return position;
    }
}