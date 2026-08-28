package com.tradingplatform.strategy;

import com.tradingplatform.candle.CandleListener;
import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.readiness.ReadinessSnapshot;
import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.domain.strategy.StrategyInstance;
import com.tradingplatform.readiness.ReadinessService;
import com.tradingplatform.strategy.confluence.ConfluenceDecision;
import com.tradingplatform.strategy.confluence.ConfluenceEngine;
import com.tradingplatform.ui.LiveTickStreamController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class StrategyEngine implements CandleListener {

    private static final Logger log = LoggerFactory.getLogger(StrategyEngine.class);
    private static final double SLOW_EVALUATION_THRESHOLD_FRACTION = 0.05;

    private final Map<String, List<StrategyBinding>> bindingsByKey = new ConcurrentHashMap<>();
    private final List<SignalListener> signalListeners = new CopyOnWriteArrayList<>();

    // Engine Stream Listeners (for streaming exact engine candles to UI/WebSocket/SSE)
    private final List<CandleStreamListener> candleStreamListeners = new CopyOnWriteArrayList<>();

    private final Map<String, Deque<Candle>> historyByKey = new ConcurrentHashMap<>();
    private final Map<String, Boolean> warmupStatusBySymbol = new ConcurrentHashMap<>();
    private final int maxHistoryPerKey;

    private final AtomicLong totalStrategyCount = new AtomicLong();

    @Autowired(required = false)
    private ConfluenceEngine confluenceEngine;

    @Autowired(required = false)
    private ReadinessService readinessService;

    @Autowired(required = false)
    private LiveTickStreamController liveTickStreamController;

    /**
     * Default constructor for Spring Boot Component Scanning - confluenceEngine will be set via setter
     */
    public StrategyEngine() {
        this(220);
    }

    public StrategyEngine(int maxHistoryPerKey) {
        this.maxHistoryPerKey = maxHistoryPerKey;
    }

    /**
     * Setter for ConfluenceEngine injection (optional dependency)
     */
    public void setConfluenceEngine(ConfluenceEngine confluenceEngine) {
        this.confluenceEngine = confluenceEngine;
    }

    /**
     * Functional interface for streaming engine candles externally (e.g., to Visualizer UI).
     */
    @FunctionalInterface
    public interface CandleStreamListener {
        void onCandle(Candle candle);
    }

    public void addCandleStreamListener(CandleStreamListener listener) {
        this.candleStreamListeners.add(listener);
    }

    public void removeCandleStreamListener(CandleStreamListener listener) {
        this.candleStreamListeners.remove(listener);
    }

    /**
     * Thread-safe snapshot getter for UI history baseline endpoints.
     */
    public List<Candle> getHistorySnapshot(String symbol, String timeframe) {
        String key = key(symbol, timeframe);
        Deque<Candle> history = historyByKey.get(key);
        if (history == null) {
            return List.of();
        }
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    /**
     * Completely clears existing historical buffer for symbol/timeframe and populates
     * a fresh batch. Prevents duplicate candles without running O(N) list searches.
     */
    public void replaceHistoricalCandles(String symbol, String timeframe, List<Candle> candleBatch) {
        String key = key(symbol, timeframe);
        Deque<Candle> history = historyByKey.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (history) {
            history.clear(); // Wipe clean on new snapshot batch
            for (Candle candle : candleBatch) {
                history.addLast(candle);
                while (history.size() > maxHistoryPerKey) {
                    history.removeFirst();
                }
                // Notify visualizer UI stream listeners per seeded candle
                notifyStreamListeners(candle);
            }
        }
        log.info("StrategyEngine: History replaced for key {} with {} candles.", key, history.size());
    }

    /**
     * Single candle historical seed method.
     */
    public void seedHistoricalCandle(Candle candle) {
        String key = key(candle.getSymbol(), candle.getTimeframe());
        Deque<Candle> history = historyByKey.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (history) {
            history.addLast(candle);
            while (history.size() > maxHistoryPerKey) {
                history.removeFirst();
            }
        }

        notifyStreamListeners(candle);
    }

    public void markWarmupComplete(String symbol) {
        warmupStatusBySymbol.put(symbol, true);
        log.info("StrategyEngine: Warmup marked complete for symbol {}", symbol);
    }

    public void register(StrategyInstance strategyInstance, TradingStrategy strategy) {
        register(strategyInstance, strategy, null);
    }

    /**
     * Registers a strategy bound to its own symbol+timeframe (the trigger that
     * causes evaluate() to run), optionally also given read-only visibility
     * into a coarser confirmationTimeframe's history via MarketContext. The
     * confirmation timeframe never triggers evaluation by itself - it's just
     * whatever history happens to be in historyByKey at the moment the
     * primary candle closes, so that timeframe must already be produced by
     * the pipeline (e.g. listed in app.candle.derived-timeframes) or this
     * strategy will simply see an empty higherTimeframeCandles list.
     */
    public void register(StrategyInstance strategyInstance, TradingStrategy strategy, String confirmationTimeframe) {
        String key = key(strategyInstance.getSymbol(), strategyInstance.getTimeframe());
        bindingsByKey.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                .add(new StrategyBinding(strategyInstance, strategy, confirmationTimeframe));
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

        List<Candle> historySnapshot;
        synchronized (history) {
            history.addLast(candle);
            while (history.size() > maxHistoryPerKey) {
                history.removeFirst();
            }
            historySnapshot = List.copyOf(history);
        }

        // Broadcast the exact closed live candle to UI visualizer
        notifyStreamListeners(candle);

        List<StrategyBinding> matching = bindingsByKey.get(key);
        if (matching == null || matching.isEmpty()) {
            return;
        }

        // Do not generate trading signals if historical warmup isn't complete yet
        if (!warmupStatusBySymbol.getOrDefault(candle.getSymbol(), false)) {
            log.debug("Skipping evaluation for {} as historical warmup is incomplete.", candle.getSymbol());
            return;
        }

        MarketContext baseContext = new MarketContext(candle.getSymbol(), candle, historySnapshot);

        long startNanos = System.nanoTime();
        List<Signal> signals = new ArrayList<>(matching.size());
        for (StrategyBinding binding : matching) {
            MarketContext context = contextFor(binding, baseContext, candle);
            Signal signal = evaluateOne(binding, context, candle);
            if (signal != null) {
                signals.add(signal);
            }
        }
        long elapsedMicros = (System.nanoTime() - startNanos) / 1_000;

        logTimingIfNotable(candle, matching.size(), elapsedMicros);

        // Pass signals through confluence engine to augment with multi-strategy consensus
        List<ConfluenceEngine.AugmentedSignal> augmentedSignals = confluenceEngine.augmentSignals(signals);

        for (ConfluenceEngine.AugmentedSignal augmented : augmentedSignals) {
            Signal signal = augmented.signal();
            ConfluenceDecision confluenceDecision = augmented.confluenceDecision();

            // Apply confluence metadata to the signal
            signal.applyConfluenceDecision(
                    confluenceDecision.type().name(),
                    java.math.BigDecimal.valueOf(confluenceDecision.weightedAgreementScore()),
                    confluenceDecision.reason()
            );

            for (SignalListener listener : signalListeners) {
                try {
                    listener.onSignal(signal);
                } catch (Exception e) {
                    log.error("SignalListener threw while handling signal for strategy instance {}: {}",
                            signal.getStrategyInstance().getId(), e.getMessage(), e);
                }
            }
        }

        // NEW: Update readiness snapshot after signal processing
        if (readinessService != null && !signals.isEmpty()) {
            try {
                // Get the consensus decision from the first augmented signal (they all share same confluence decision)
                ConfluenceDecision confluenceDecision = augmentedSignals.isEmpty()
                        ? null
                        : augmentedSignals.get(0).confluenceDecision();

                ReadinessSnapshot snapshot = readinessService.updateReadiness(
                        candle.getSymbol(),
                        candle.getTimeframe(),
                        signals,
                        confluenceDecision
                );

                // Broadcast readiness update to SSE clients
                if (liveTickStreamController != null) {
                    liveTickStreamController.onReadinessUpdate(snapshot);
                }

                log.debug("Readiness updated and broadcast for {}/{}: {}% ready",
                        candle.getSymbol(), candle.getTimeframe(), snapshot.getReadinessPercent());

            } catch (Exception e) {
                log.warn("Failed to update readiness for {}/{}: {}",
                        candle.getSymbol(), candle.getTimeframe(), e.getMessage());
            }
        }
    }

    /**
     * Builds a per-binding context: reuses the shared base context for
     * strategies with no confirmation timeframe (the common case, no extra
     * lookup), otherwise attaches a snapshot of the confirmation timeframe's
     * current history. That history is a plain map read - it's whatever the
     * confirmation timeframe's own onCandleClosed calls have accumulated so
     * far, no coupling to this binding's evaluation cadence.
     */
    private MarketContext contextFor(StrategyBinding binding, MarketContext baseContext, Candle candle) {
        String confirmationTimeframe = binding.confirmationTimeframe();
        if (confirmationTimeframe == null || confirmationTimeframe.isBlank()) {
            return baseContext;
        }
        List<Candle> higherTimeframeCandles = getHistorySnapshot(candle.getSymbol(), confirmationTimeframe);
        return new MarketContext(candle.getSymbol(), candle, baseContext.recentCandles(),
                confirmationTimeframe, higherTimeframeCandles);
    }

    private void notifyStreamListeners(Candle candle) {
        for (CandleStreamListener listener : candleStreamListeners) {
            try {
                listener.onCandle(candle);
            } catch (Exception e) {
                log.error("CandleStreamListener threw while handling candle for {}: {}",
                        candle.getSymbol(), e.getMessage(), e);
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

    /**
     * Appends a batch of candles to existing history without wiping previous chunks.
     */
    public void appendHistoricalCandles(String symbol, String timeframe, List<Candle> candleBatch) {
        String key = key(symbol, timeframe);
        Deque<Candle> history = historyByKey.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (history) {
            for (Candle candle : candleBatch) {
                history.addLast(candle);
                while (history.size() > maxHistoryPerKey) {
                    history.removeFirst();
                }
                notifyStreamListeners(candle);
            }
        }
        log.info("StrategyEngine: Appended {} historical candles to key {}. Total history size: {}",
                candleBatch.size(), key, history.size());
    }

    /**
     * Standardized key builder to avoid string mismatches between symbol and timeframe formats
     */
    private static String key(String symbol, String timeframe) {
        if (symbol == null || timeframe == null) {
            return "";
        }
        String tf = timeframe.trim().toLowerCase();
        if ("60s".equals(tf)) {
            tf = "1m"; // Convert 60s to 1m
        }
        return symbol.trim().toUpperCase() + "|" + tf;
    }

    private record StrategyBinding(StrategyInstance strategyInstance, TradingStrategy strategy,
                                   String confirmationTimeframe) {
    }
}