package com.tradingplatform.domain.candle;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persisted OHLCV candle. Only base-timeframe candles are stored (see
 * CandleArchivingListener) - higher timeframes are always derivable from
 * base rows, so storing them too would be redundant. This table doubles
 * as a live archive (grows as the app runs) and, later, the destination
 * for bulk-imported FYERS historical data - same shape either way, so
 * backtests won't care whether a row came from live trading or a
 * historical fetch.
 */
@Entity
@Table(name = "market_candle", uniqueConstraints = {
        @UniqueConstraint(name = "uk_market_candle_symbol_timeframe_window",
                columnNames = {"symbol", "timeframe", "window_start"})
})
public class MarketCandle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false, length = 16)
    private String timeframe;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal open;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal high;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal low;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal close;

    @Column(nullable = false)
    private long volume;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MarketCandle() {
    }

    public MarketCandle(String symbol, String timeframe, Instant windowStart, Instant windowEnd,
                        BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public String getTimeframe() { return timeframe; }
    public Instant getWindowStart() { return windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public BigDecimal getOpen() { return open; }
    public BigDecimal getHigh() { return high; }
    public BigDecimal getLow() { return low; }
    public BigDecimal getClose() { return close; }
    public long getVolume() { return volume; }
    public Instant getCreatedAt() { return createdAt; }
}