package com.tradingplatform.indicator;

import com.tradingplatform.domain.candle.Candle;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Bollinger Bands: a middle SMA with upper/lower bands offset by
 * `stdDevMultiplier` standard deviations of closes in the same window.
 * Keeps a sliding window of the last `period` closes (like
 * SimpleMovingAverage) since standard deviation, unlike a sum, can't be
 * updated purely incrementally without re-deriving it from the window -
 * this is O(period) per update rather than O(1), which is acceptable
 * since `period` is small (typically 20).
 */
public class BollingerBands implements Indicator<BollingerBands.BandValue> {

    public record BandValue(BigDecimal middle, BigDecimal upper, BigDecimal lower) {
    }

    private static final MathContext MC = MathContext.DECIMAL64;

    private final int period;
    private final BigDecimal stdDevMultiplier;
    private final Deque<BigDecimal> window = new ArrayDeque<>();

    public BollingerBands(int period, BigDecimal stdDevMultiplier) {
        if (period < 2) {
            throw new IllegalArgumentException("period must be >= 2, was " + period);
        }
        this.period = period;
        this.stdDevMultiplier = stdDevMultiplier;
    }

    public BollingerBands() {
        this(20, BigDecimal.valueOf(2.0));
    }

    @Override
    public void update(Candle candle) {
        window.addLast(candle.getClose());
        if (window.size() > period) {
            window.removeFirst();
        }
    }

    @Override
    public boolean isReady() {
        return window.size() >= period;
    }

    @Override
    public Optional<BandValue> value() {
        if (!isReady()) {
            return Optional.empty();
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal close : window) {
            sum = sum.add(close);
        }
        BigDecimal mean = sum.divide(BigDecimal.valueOf(period), MC);

        BigDecimal sumSquaredDiff = BigDecimal.ZERO;
        for (BigDecimal close : window) {
            BigDecimal diff = close.subtract(mean);
            sumSquaredDiff = sumSquaredDiff.add(diff.multiply(diff));
        }
        BigDecimal variance = sumSquaredDiff.divide(BigDecimal.valueOf(period), MC);
        BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));

        BigDecimal offset = stdDev.multiply(stdDevMultiplier);
        return Optional.of(new BandValue(mean, mean.add(offset), mean.subtract(offset)));
    }
}
