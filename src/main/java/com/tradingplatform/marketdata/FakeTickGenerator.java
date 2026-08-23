package com.tradingplatform.marketdata;

import com.tradingplatform.domain.tick.Tick;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Emits synthetic ticks for one or more symbols on a fixed schedule using a
 * simple random walk for price. This exists purely so we can build and test
 * the candle engine and (later) the strategy engine at any time of day,
 * without a FYERS connection or market hours getting in the way.
 *
 * When we integrate FYERS (later phase), FyersWebSocketTickSource will
 * implement this same TickSource interface and can be swapped in without
 * changing CandleBuilder or anything downstream.
 */
public class FakeTickGenerator implements TickSource {

    private final List<TickListener> listeners = new CopyOnWriteArrayList<>();
    private final List<String> symbols;
    private final long intervalMillis;
    private final Random random = new Random();

    private ScheduledExecutorService scheduler;

    // Tracks the last price per symbol so the random walk is continuous
    // rather than jumping to a fresh random price on every tick.
    private final java.util.Map<String, BigDecimal> lastPrice = new java.util.concurrent.ConcurrentHashMap<>();

    public FakeTickGenerator(List<String> symbols, long intervalMillis) {
        this.symbols = symbols;
        this.intervalMillis = intervalMillis;
        for (String symbol : symbols) {
            // Arbitrary realistic-ish starting price; doesn't matter for Phase 1.
            lastPrice.put(symbol, BigDecimal.valueOf(20000 + random.nextInt(5000)));
        }
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fake-tick-generator");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::emitTickForEachSymbol, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public void addListener(TickListener listener) {
        listeners.add(listener);
    }

    private void emitTickForEachSymbol() {
        for (String symbol : symbols) {
            Tick tick = nextTick(symbol);
            for (TickListener listener : listeners) {
                listener.onTick(tick);
            }
        }
    }

    private Tick nextTick(String symbol) {
        BigDecimal previous = lastPrice.get(symbol);

        // Random walk: +/- up to 0.15% of current price per tick.
        double changePercent = (random.nextDouble() - 0.5) * 0.003;
        BigDecimal change = previous.multiply(BigDecimal.valueOf(changePercent));
        BigDecimal next = previous.add(change).setScale(2, RoundingMode.HALF_UP);

        lastPrice.put(symbol, next);

        long volume = 1 + random.nextInt(500);

        return new Tick(symbol, Instant.now(), next, volume);
    }
}
