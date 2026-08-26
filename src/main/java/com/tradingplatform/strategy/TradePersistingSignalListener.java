package com.tradingplatform.strategy;

import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.domain.trade.ExecutionMode;
import com.tradingplatform.domain.trade.Trade;
import com.tradingplatform.domain.trade.TradeRepository;
import com.tradingplatform.domain.trade.TradeSide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists paper trades to PostgreSQL whenever a simulated position opens or closes.
 * Not a @Component — manually instantiated in MarketDataPipelineRunner so it shares
 * the same lifecycle as other signal listeners.
 */
public class TradePersistingSignalListener implements SignalListener {

    private final TradeRepository tradeRepository;
    private final Map<Long, TradeSide> openPositions = new ConcurrentHashMap<>();

    public TradePersistingSignalListener(TradeRepository tradeRepository) {
        this.tradeRepository = tradeRepository;
    }

    @Override
    public void onSignal(Signal signal) {
        if (signal == null || signal.getStrategyInstance() == null) return;
        if (signal.getSignalType() == SignalType.HOLD) return;

        Long instanceId = signal.getStrategyInstance().getId();
        TradeSide currentSide = openPositions.get(instanceId);

        if (currentSide == null) {
            // No open position — open one
            TradeSide newSide = signal.getSignalType() == SignalType.BUY ? TradeSide.BUY : TradeSide.SELL;
            openPositions.put(instanceId, newSide);
            saveTrade(signal, newSide);
        } else if ((currentSide == TradeSide.BUY && signal.getSignalType() == SignalType.SELL)
                || (currentSide == TradeSide.SELL && signal.getSignalType() == SignalType.BUY)) {
            // Opposite signal — close existing position
            TradeSide closingSide = signal.getSignalType() == SignalType.BUY ? TradeSide.BUY : TradeSide.SELL;
            saveTrade(signal, closingSide);
            openPositions.remove(instanceId);
        }
        // Same direction as open position — ignore (no pyramiding)
    }

    private void saveTrade(Signal signal, TradeSide side) {
        Trade trade = new Trade(
                signal.getStrategyInstance(),
                signal,
                signal.getSymbol(),
                side,
                BigDecimal.ONE,
                signal.getPrice(),
                ExecutionMode.PAPER,
                Instant.now()
        );
        tradeRepository.save(trade);
    }
}