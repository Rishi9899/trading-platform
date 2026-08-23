package com.tradingplatform.domain.candle;

import java.math.BigDecimal;
import java.time.Instant;

public final class Candle {

    private final String symbol;
    private final String timeframe;
    private final Instant windowStart;
    private final Instant windowEnd;
    private final BigDecimal open;
    private final BigDecimal high;
    private final BigDecimal low;
    private final BigDecimal close;
    private final long volume;

    public Candle(String symbol, String timeframe, Instant windowStart, Instant windowEnd,
                  BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                  long volume) {
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public String getSymbol() { return symbol; }
    public String getTimeframe() { return timeframe; }
    public Instant getWindowStart() { return windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public BigDecimal getOpen() { return open; }
    public BigDecimal getHigh() { return high; }
    public BigDecimal getLow() { return low; }
    public BigDecimal getClose() { return close; }
    public long getVolume() { return volume; }

    @Override
    public String toString() {
        return "Candle{symbol='%s', timeframe='%s', window=%s->%s, O=%s H=%s L=%s C=%s, vol=%d}"
                .formatted(symbol, timeframe, windowStart, windowEnd, open, high, low, close, volume);
    }
}