package com.tradingplatform.indicator;

import com.tradingplatform.domain.candle.Candle;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * O(1) per update via a sliding window: keeps a running sum and only the
 * last `period` closes, rather than re-summing the whole window every
 * time.
 */
public class SimpleMovingAverage implements Indicator<BigDecimal> {

    private static final MathContext MC = MathContext.DECIMAL64;

    private final int period;
    private final Deque<BigDecimal> window = new ArrayDeque<>();
    private BigDecimal sum = BigDecimal.ZERO;

    public SimpleMovingAverage(int period) {
        if (period < 1) {
            throw new IllegalArgumentException("period must be >= 1, was " + period);
        }
        this.period = period;
    }

    @Override
    public void update(Candle candle) {
        BigDecimal close = candle.getClose();
        window.addLast(close);
        sum = sum.add(close);
        if (window.size() > period) {
            sum = sum.subtract(window.removeFirst());
        }
    }

    @Override
    public boolean isReady() {
        return window.size() >= period;
    }

    @Override
    public Optional<BigDecimal> value() {
        if (!isReady()) {
            return Optional.empty();
        }
        return Optional.of(sum.divide(BigDecimal.valueOf(period), MC));
    }
}