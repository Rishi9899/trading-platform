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
 * Composes a higher timeframe (e.g., 5m) from lower base timeframe candles (e.g., 1m).
 *
 * Implements gap recovery with synthetic forward-filling so strategy and technical indicator
 * calculations never fail due to missing market data or network drops.
 */
public class CandleAggregator implements CandleListener {

    private static final Logger log = LoggerFactory.getLogger(CandleAggregator.class);

    private final Duration baseTimeframe;
    private final Duration targetTimeframe;
    private final String targetLabel;
    private final int expectedBaseCandleCount;

    private final Map<String, WindowBucket> bucketsBySymbol = new HashMap<>();
    private final Map<String, Candle> lastKnownBaseCandleBySymbol = new HashMap<>();
    private final List<CandleListener> listeners = new CopyOnWriteArrayList<>();

    public CandleAggregator(Duration baseTimeframe, Duration targetTimeframe, String targetLabel) {
        if (targetTimeframe.getSeconds() <= baseTimeframe.getSeconds()
                || targetTimeframe.getSeconds() % baseTimeframe.getSeconds() != 0) {
            throw new IllegalArgumentException("targetTimeframe (" + targetTimeframe
                    + ") must be a whole multiple of baseTimeframe (" + baseTimeframe + ")");
        }
        this.baseTimeframe = baseTimeframe;
        this.targetTimeframe = targetTimeframe;
        this.targetLabel = targetLabel;
        this.expectedBaseCandleCount = (int) (targetTimeframe.getSeconds() / baseTimeframe.getSeconds());
    }

    public void addListener(CandleListener listener) {
        listeners.add(listener);
    }

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
        lastKnownBaseCandleBySymbol.put(symbol, baseCandle);
    }

    private void flush(String symbol, WindowBucket bucket) {
        if (bucket.baseCandles.isEmpty()) {
            return;
        }

        List<Candle> completeBaseList = synthesizeMissingBaseCandles(symbol, bucket);

        BigDecimal open = completeBaseList.get(0).getOpen();
        BigDecimal close = completeBaseList.get(completeBaseList.size() - 1).getClose();
        BigDecimal high = completeBaseList.get(0).getHigh();
        BigDecimal low = completeBaseList.get(0).getLow();
        long volume = 0;

        for (Candle c : completeBaseList) {
            if (c.getHigh().compareTo(high) > 0) high = c.getHigh();
            if (c.getLow().compareTo(low) < 0) low = c.getLow();
            volume += c.getVolume();
        }

        Candle aggregated = new Candle(
                symbol,
                targetLabel,
                bucket.windowStart,
                bucket.windowStart.plus(targetTimeframe),
                open,
                high,
                low,
                close,
                volume
        );

        for (CandleListener listener : listeners) {
            listener.onCandleClosed(aggregated);
        }
    }

    private List<Candle> synthesizeMissingBaseCandles(String symbol, WindowBucket bucket) {
        if (bucket.baseCandles.size() == expectedBaseCandleCount) {
            return bucket.baseCandles;
        }

        log.warn("Incomplete {} window for {} at {}: got {}/{} base candles. Forward-filling missing slots.",
                targetLabel, symbol, bucket.windowStart, bucket.baseCandles.size(), expectedBaseCandleCount);

        List<Candle> filledList = new ArrayList<>(expectedBaseCandleCount);
        Map<Instant, Candle> presentCandles = new HashMap<>();
        for (Candle c : bucket.baseCandles) {
            presentCandles.put(c.getWindowStart(), c);
        }

        Candle lastSeen = lastKnownBaseCandleBySymbol.get(symbol);
        Instant currentSlot = bucket.windowStart;

        for (int i = 0; i < expectedBaseCandleCount; i++) {
            Candle actual = presentCandles.get(currentSlot);
            if (actual != null) {
                filledList.add(actual);
                lastSeen = actual;
            } else {
                BigDecimal fallbackPrice = (lastSeen != null)
                        ? lastSeen.getClose()
                        : bucket.baseCandles.get(0).getOpen();

                Candle synthetic = new Candle(
                        symbol,
                        "synthetic-base",
                        currentSlot,
                        currentSlot.plus(baseTimeframe),
                        fallbackPrice,
                        fallbackPrice,
                        fallbackPrice,
                        fallbackPrice,
                        0L
                );
                filledList.add(synthetic);
            }
            currentSlot = currentSlot.plus(baseTimeframe);
        }

        return filledList;
    }

    private static final class WindowBucket {
        final Instant windowStart;
        final List<Candle> baseCandles = new ArrayList<>();

        WindowBucket(Instant windowStart) {
            this.windowStart = windowStart;
        }
    }
}