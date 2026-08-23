package com.tradingplatform.domain.tick;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single market tick: one price update for one symbol at one instant.
 *
 * This is deliberately minimal for Phase 1. When we integrate FYERS in a
 * later phase, we'll check the actual WebSocket payload and extend this
 * (bid/ask, previousClose, etc.) only with fields we actually use -
 * see Phase 4 in the roadmap.
 *
 * Immutable by design: a tick is a fact that already happened, it never
 * changes after creation.
 */
public final class Tick {

    private final String symbol;
    private final Instant timestamp;
    private final BigDecimal price;
    private final long volume;

    public Tick(String symbol, Instant timestamp, BigDecimal price, long volume) {
        this.symbol = symbol;
        this.timestamp = timestamp;
        this.price = price;
        this.volume = volume;
    }

    public String getSymbol() {
        return symbol;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getVolume() {
        return volume;
    }

    @Override
    public String toString() {
        return "Tick{symbol='%s', timestamp=%s, price=%s, volume=%d}"
                .formatted(symbol, timestamp, price, volume);
    }
}
