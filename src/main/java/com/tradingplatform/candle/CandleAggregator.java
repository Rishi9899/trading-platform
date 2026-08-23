package com.tradingplatform.candle;

import com.tradingplatform.domain.candle.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Composes a higher timeframe (e.g. 5m) from consecutive base-timeframe
 * candles (e.g. 1m) - without re-processing raw ticks. One ingestion
 * cost (the base CandleBuilder), many derived views: N strategies on N
 * different timeframes for the same symbol don't each reprocess the
 * tick stream independently.
 *
 * NOT thread-safe by design: this is only ever driven synchronously by
 * whichever thread emits base candles (in this pipeline, the single
 * TickEventQueue consumer thread, via CandleBuilder.emit()). If a future
 * change ever feeds this from multiple threads concurrently, add
 * synchronization then - deliberately not adding it preemptively.
 *
 * If ticks were dropped or a symbol had a gap, a target window may not
 * receive its full complement of base candles. Rather than emit a
 * misleading partial candle, that window is skipped and logged - same
 * "drop and log rather than silently produce wrong data" approach
 * CandleBuilder uses for late ticks.
 */
public class CandleAggregator implements CandleListener {

    private static final Logger log = LoggerFactory.getLogger(CandleAggregator.class);

    private final Duration targetTimeframe;
    private final String targetLabel;
    private final int expectedBaseCandleCount;

    private final Map<String, WindowBucket> bucketsBySymbol = new HashMap<>();
    private final List<CandleListener> listeners = new CopyOnWriteArrayList<>();

    public CandleAggregator(Duration baseTimeframe, Duration targetTimeframe, String targetLabel) {
        if (targetTimeframe.getSeconds() <= baseTimeframe.getSeconds()
                || targetTimeframe.getSeconds() % baseTimeframe.getSeconds() != 0) {
            throw new IllegalArgumentException("targetTimeframe (" + targetTimeframe
                    + ") must be a whole multiple of baseTimeframe (" + baseTimeframe + ")");
        }
        this.targetTimeframe = targetTimeframe;
        this.targetLabel = targetLabel;
        this.expectedBaseCandleCount = (int) (targetTimeframe.getSeconds() / baseTimeframe.getSeconds());
    }

    public void addListener(CandleListener listener) {
        listeners.add(listener);
    }

    /** Feed this a base-timeframe candle - register it as a listener on the base CandleBuilder. */
    @Override
    public void onCandleClosed(Candle baseCandle) {
        Instant targetWindowStart = CandleWindow.alignDown(baseCandle.getWindowStart(), targetTimeframe);
        String symbol = baseCandle.getSymbol();

        WindowBucket bucket = bucketsBySymbol.get(symbol);
        if (bucket == null) {
            bucket = new WindowBucket(targetWindowStart);
            bucketsBySymbol.put(symbol, bucket);
        } else if (!bucket.windowStart.equals(targetWindowStart)) {
            flush(symbol, bucket);
            bucket = new WindowBucket(targetWindowStart);
            bucketsBySymbol.put(symbol, bucket);
        }

        bucket.baseCandles.add(baseCandle);
    }

    private void flush(String symbol, WindowBucket bucket) {
        if (bucket.baseCandles.isEmpty()) {
            return;
        }
        if (bucket.baseCandles.size() != expectedBaseCandleCount) {
            log.warn("Skipping incomplete {} window for {} at {}: got {}/{} base candles (likely a gap upstream)",
                    targetLabel, symbol, bucket.windowStart, bucket.baseCandles.size(), expectedBaseCandleCount);
            return;
        }

        List<Candle> ordered = bucket.baseCandles;
        BigDecimal open = ordered.get(0).getOpen();
        BigDecimal close = ordered.get(ordered.size() - 1).getClose();
        BigDecimal high = ordered.get(0).getHigh();
        BigDecimal low = ordered.get(0).getLow();
        long volume = 0;
        for (Candle c : ordered) {
            if (c.getHigh().compareTo(high) > 0) high = c.getHigh();
            if (c.getLow().compareTo(low) < 0) low = c.getLow();
            volume += c.getVolume();
        }

        Candle aggregated = new Candle(symbol, targetLabel, bucket.windowStart, bucket.windowStart.plus(targetTimeframe),
                open, high, low, close, volume);

        for (CandleListener listener : listeners) {
            listener.onCandleClosed(aggregated);
        }
    }

    private static final class WindowBucket {
        final Instant windowStart;
        final List<Candle> baseCandles = new ArrayList<>();

        WindowBucket(Instant windowStart) {
            this.windowStart = windowStart;
        }
    }
}