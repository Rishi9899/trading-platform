package com.tradingplatform.candle;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.tick.Tick;
import com.tradingplatform.marketdata.TickListener;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class CandleBuilder implements TickListener {

    private final Duration timeframe;
    private final String timeframeLabel;
    private final List<CandleListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, WorkingCandle> inProgress = new ConcurrentHashMap<>();

    public CandleBuilder(Duration timeframe, String timeframeLabel) {
        this.timeframe = timeframe;
        this.timeframeLabel = timeframeLabel;
    }

    public void addListener(CandleListener listener) {
        listeners.add(listener);
    }

    @Override
    public synchronized void onTick(Tick tick) {
        Instant windowStart = CandleWindow.alignDown(tick.getTimestamp(), timeframe);
        WorkingCandle current = inProgress.get(tick.getSymbol());

        if (current == null) {
            inProgress.put(tick.getSymbol(), WorkingCandle.openWith(tick, windowStart, timeframe, timeframeLabel));
            return;
        }

        if (windowStart.isBefore(current.windowStart)) {
            System.out.printf("[CandleBuilder] Dropping late tick for %s: tick=%s currentWindow=%s%n",
                    tick.getSymbol(), tick.getTimestamp(), current.windowStart);
            return;
        }

        if (windowStart.equals(current.windowStart)) {
            current.update(tick);
            return;
        }

        Candle closed = current.toCandle();
        emit(closed);
        inProgress.put(tick.getSymbol(), WorkingCandle.openWith(tick, windowStart, timeframe, timeframeLabel));
    }

    private void emit(Candle candle) {
        for (CandleListener listener : listeners) {
            listener.onCandleClosed(candle);
        }
    }

    private static final class WorkingCandle {
        final String symbol;
        final String timeframeLabel;
        final Instant windowStart;
        final Instant windowEnd;
        java.math.BigDecimal open;
        java.math.BigDecimal high;
        java.math.BigDecimal low;
        java.math.BigDecimal close;
        long volume;

        private WorkingCandle(String symbol, String timeframeLabel, Instant windowStart, Instant windowEnd, Tick firstTick) {
            this.symbol = symbol;
            this.timeframeLabel = timeframeLabel;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.open = firstTick.getPrice();
            this.high = firstTick.getPrice();
            this.low = firstTick.getPrice();
            this.close = firstTick.getPrice();
            this.volume = firstTick.getVolume();
        }

        static WorkingCandle openWith(Tick tick, Instant windowStart, Duration timeframe, String timeframeLabel) {
            return new WorkingCandle(tick.getSymbol(), timeframeLabel, windowStart, windowStart.plus(timeframe), tick);
        }

        void update(Tick tick) {
            if (tick.getPrice().compareTo(high) > 0) high = tick.getPrice();
            if (tick.getPrice().compareTo(low) < 0) low = tick.getPrice();
            close = tick.getPrice();
            volume += tick.getVolume();
        }

        Candle toCandle() {
            return new Candle(symbol, timeframeLabel, windowStart, windowEnd, open, high, low, close, volume);
        }
    }
}