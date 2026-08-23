package com.tradingplatform.strategy;

import com.tradingplatform.candle.CandleListener;
import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.domain.strategy.StrategyInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class StrategyEngine implements CandleListener {

    private static final Logger log = LoggerFactory.getLogger(StrategyEngine.class);

    private final List<StrategyBinding> bindings = new CopyOnWriteArrayList<>();
    private final List<SignalListener> signalListeners = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Candle>> historyByKey = new ConcurrentHashMap<>();
    private final int maxHistoryPerKey;

    public StrategyEngine(int maxHistoryPerKey) {
        this.maxHistoryPerKey = maxHistoryPerKey;
    }

    public void register(StrategyInstance strategyInstance, TradingStrategy strategy) {
        bindings.add(new StrategyBinding(strategyInstance, strategy));
    }

    public void addSignalListener(SignalListener listener) {
        signalListeners.add(listener);
    }

    @Override
    public void onCandleClosed(Candle candle) {
        String historyKey = historyKey(candle.getSymbol(), candle.getTimeframe());
        Deque<Candle> history = historyByKey.computeIfAbsent(historyKey, k -> new ArrayDeque<>());
        history.addLast(candle);
        while (history.size() > maxHistoryPerKey) {
            history.removeFirst();
        }

        MarketContext context = new MarketContext(candle.getSymbol(), candle, List.copyOf(history));

        for (StrategyBinding binding : bindings) {
            StrategyInstance instance = binding.strategyInstance();
            if (!instance.getSymbol().equals(candle.getSymbol())
                    || !instance.getTimeframe().equals(candle.getTimeframe())) {
                continue;
            }
            evaluateOne(binding, context, candle);
        }
    }

    private void evaluateOne(StrategyBinding binding, MarketContext context, Candle candle) {
        try {
            StrategyDecision decision = binding.strategy().evaluate(context);
            if (decision == null) {
                return;
            }
            Signal signal = new Signal(
                    binding.strategyInstance(),
                    candle.getSymbol(),
                    candle.getWindowEnd(),
                    decision.signalType(),
                    decision.price(),
                    decision.confidence(),
                    decision.reason()
            );
            for (SignalListener listener : signalListeners) {
                listener.onSignal(signal);
            }
        } catch (Exception e) {
            log.error("Strategy instance {} threw during evaluation: {}",
                    binding.strategyInstance().getId(), e.getMessage(), e);
        }
    }

    private static String historyKey(String symbol, String timeframe) {
        return symbol + "|" + timeframe;
    }

    private record StrategyBinding(StrategyInstance strategyInstance, TradingStrategy strategy) {
    }
}