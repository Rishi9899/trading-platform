package com.tradingplatform.indicator;

import com.tradingplatform.domain.candle.Candle;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

/**
 * Naturally O(1): each update only needs the previous EMA value, never
 * the full history. Seeded with a Simple Moving Average of the first
 * `period` closes (the standard approach), then the exponential formula
 * takes over from there.
 */
public class ExponentialMovingAverage implements Indicator<BigDecimal> {

    private static final MathContext MC = MathContext.DECIMAL64;

    private final int period;
    private final BigDecimal multiplier;
    private final SimpleMovingAverage seed;

    private BigDecimal current;
    private boolean seeded = false;

    public ExponentialMovingAverage(int period) {
        if (period < 1) {
            throw new IllegalArgumentException("period must be >= 1, was " + period);
        }
        this.period = period;
        this.multiplier = BigDecimal.valueOf(2.0).divide(BigDecimal.valueOf(period + 1), MC);
        this.seed = new SimpleMovingAverage(period);
    }

    @Override
    public void update(Candle candle) {
        if (!seeded) {
            seed.update(candle);
            if (seed.isReady()) {
                current = seed.value().orElseThrow();
                seeded = true;
            }
            return;
        }
        BigDecimal close = candle.getClose();
        current = close.subtract(current).multiply(multiplier).add(current);
    }

    @Override
    public boolean isReady() {
        return seeded;
    }

    @Override
    public Optional<BigDecimal> value() {
        return seeded ? Optional.of(current) : Optional.empty();
    }
}