package com.tradingplatform.indicator;

import com.tradingplatform.domain.candle.Candle;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

/**
 * ATR using Wilder's smoothing (same technique as RelativeStrengthIndex) -
 * O(1) per update via a running average of true range, never a recompute
 * over history. True range is the largest of: high-low, |high-prevClose|,
 * |low-prevClose| - it needs a previous close, so the first candle only
 * establishes that baseline and doesn't produce a reading.
 */
public class AverageTrueRange implements Indicator<BigDecimal> {

    private static final MathContext MC = MathContext.DECIMAL64;

    private final int period;

    private BigDecimal previousClose;
    private BigDecimal avgTrueRange = BigDecimal.ZERO;
    private int sampleCount = 0;
    private boolean ready = false;

    public AverageTrueRange(int period) {
        if (period < 1) {
            throw new IllegalArgumentException("period must be >= 1, was " + period);
        }
        this.period = period;
    }

    @Override
    public void update(Candle candle) {
        if (previousClose == null) {
            previousClose = candle.getClose();
            return;
        }

        BigDecimal highLow = candle.getHigh().subtract(candle.getLow());
        BigDecimal highPrevClose = candle.getHigh().subtract(previousClose).abs();
        BigDecimal lowPrevClose = candle.getLow().subtract(previousClose).abs();
        BigDecimal trueRange = highLow.max(highPrevClose).max(lowPrevClose);

        if (sampleCount < period) {
            avgTrueRange = avgTrueRange.add(trueRange);
            sampleCount++;
            if (sampleCount == period) {
                avgTrueRange = avgTrueRange.divide(BigDecimal.valueOf(period), MC);
                ready = true;
            }
        } else {
            BigDecimal periodBd = BigDecimal.valueOf(period);
            avgTrueRange = avgTrueRange.multiply(periodBd.subtract(BigDecimal.ONE))
                    .add(trueRange)
                    .divide(periodBd, MC);
        }

        previousClose = candle.getClose();
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public Optional<BigDecimal> value() {
        return ready ? Optional.of(avgTrueRange) : Optional.empty();
    }
}
