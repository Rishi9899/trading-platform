package com.tradingplatform.strategy;

import com.tradingplatform.candle.CandleListener;
import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.domain.strategy.StrategyInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class StrategyEngine implements CandleListener {

    private static final Logger log = LoggerFactory.getLogger(StrategyEngine.class);
    private static final double SLOW_EVALUATION_THRESHOLD_FRACTION = 0.05;

    private final Map<String, List<StrategyBinding>> bindingsByKey = new ConcurrentHashMap<>();
    private final List<SignalListener> signalListeners = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Candle>> historyByKey = new ConcurrentHashMap<>();
    private final Map<String, Boolean> warmupStatusBySymbol = new ConcurrentHashMap<>();
    private final int maxHistoryPerKey;

    private final AtomicLong totalStrategyCount = new AtomicLong();

    public StrategyEngine(int maxHistoryPerKey) {
        this.maxHistoryPerKey = maxHistoryPerKey;
    }

    /**
     * Populates history buffers without firing strategy evaluations or signals.
     */
    public void seedHistoricalCandle(Candle candle) {
        String key = key(candle.getSymbol(), candle.getTimeframe());
        Deque<Candle> history = historyByKey.computeIfAbsent(key, k -> new ArrayDeque<>());

        // Prevent duplicate candles
        if (!history.isEmpty() && history.peekLast().getWindowStart().equals(candle.getWindowStart())) {
            return;
        }

        history.addLast(candle);
        while (history.size() > maxHistoryPerKey) {
            history.removeFirst();
        }
    }

    public void markWarmupComplete(String symbol) {
        warmupStatusBySymbol.put(symbol, true);
        log.info("StrategyEngine: Warmup marked complete for symbol {}", symbol);
    }

    public void register(StrategyInstance strategyInstance, TradingStrategy strategy) {
        String key = key(strategyInstance.getSymbol(), strategyInstance.getTimeframe());
        bindingsByKey.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                .add(new StrategyBinding(strategyInstance, strategy));
        totalStrategyCount.incrementAndGet();
    }

    public void addSignalListener(SignalListener listener) {
        signalListeners.add(listener);
    }

    public long getTotalRegisteredStrategyCount() {
        return totalStrategyCount.get();
    }

    @Override
    public void onCandleClosed(Candle candle) {
        String key = key(candle.getSymbol(), candle.getTimeframe());

        Deque<Candle> history = historyByKey.computeIfAbsent(key, k -> new ArrayDeque<>());
        history.addLast(candle);
        while (history.size() > maxHistoryPerKey) {
            history.removeFirst();
        }

        List<StrategyBinding> matching = bindingsByKey.get(key);
        if (matching == null || matching.isEmpty()) {
            return;
        }

        // Do not generate trading signals if historical warmup isn't complete yet
        if (!warmupStatusBySymbol.getOrDefault(candle.getSymbol(), false)) {
            log.debug("Skipping evaluation for {} as historical warmup is incomplete.", candle.getSymbol());
            return;
        }

        MarketContext context = new MarketContext(candle.getSymbol(), candle, List.copyOf(history));

        long startNanos = System.nanoTime();
        List<Signal> signals = new ArrayList<>(matching.size());
        for (StrategyBinding binding : matching) {
            Signal signal = evaluateOne(binding, context, candle);
            if (signal != null) {
                signals.add(signal);
            }
        }
        long elapsedMicros = (System.nanoTime() - startNanos) / 1_000;

        logTimingIfNotable(candle, matching.size(), elapsedMicros);

        for (Signal signal : signals) {
            for (SignalListener listener : signalListeners) {
                try {
                    listener.onSignal(signal);
                } catch (Exception e) {
                    log.error("SignalListener threw while handling signal for strategy instance {}: {}",
                            signal.getStrategyInstance().getId(), e.getMessage(), e);
                }
            }
        }
    }

    private Signal evaluateOne(StrategyBinding binding, MarketContext context, Candle candle) {
        try {
            StrategyDecision decision = binding.strategy().evaluate(context);
            if (decision == null) {
                return null;
            }
            return new Signal(
                    binding.strategyInstance(),
                    candle.getSymbol(),
                    candle.getTimeframe(),
                    candle.getWindowEnd(),
                    decision.signalType(),
                    decision.price(),
                    decision.confidence(),
                    decision.reason()
            );
        } catch (Exception e) {
            log.error("Strategy instance {} threw during evaluation: {}",
                    binding.strategyInstance().getId(), e.getMessage(), e);
            return null;
        }
    }

    private void logTimingIfNotable(Candle candle, int strategyCount, long elapsedMicros) {
        long timeframeMicros = java.time.Duration.between(candle.getWindowStart(), candle.getWindowEnd())
                .toMillis() * 1000L;
        if (timeframeMicros <= 0) {
            return;
        }
        double fraction = elapsedMicros / (double) timeframeMicros;
        if (fraction >= SLOW_EVALUATION_THRESHOLD_FRACTION) {
            log.warn("Evaluated {} strategies for {}/{} in {}\u00b5s - {}% of the {} candle window.",
                    strategyCount, candle.getSymbol(), candle.getTimeframe(), elapsedMicros,
                    String.format("%.1f", fraction * 100), candle.getTimeframe());
        }
    }

    private static String key(String symbol, String timeframe) {
        return symbol + "|" + timeframe;
    }

    private record StrategyBinding(StrategyInstance strategyInstance, TradingStrategy strategy) {
    }
}