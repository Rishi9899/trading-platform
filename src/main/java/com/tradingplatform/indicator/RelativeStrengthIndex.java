package com.tradingplatform.indicator;

import com.tradingplatform.domain.candle.Candle;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

/**
 * RSI using Wilder's smoothing method - O(1) per update, tracking only
 * a running average gain/loss rather than the full price-change history.
 * Needs period+1 candles before it's ready (the first candle only
 * establishes a starting close, with no change to measure yet).
 *
 * value() returns full MathContext precision, unrounded - strategy
 * threshold comparisons must use the actual computed value, not a
 * display-rounded approximation. Round only at the point of logging or
 * building a human-readable explanation, never internally here.
 */
public class RelativeStrengthIndex implements Indicator<BigDecimal> {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final int period;

    private BigDecimal previousClose;
    private BigDecimal avgGain = BigDecimal.ZERO;
    private BigDecimal avgLoss = BigDecimal.ZERO;
    private int changeCount = 0;
    private boolean ready = false;

    public RelativeStrengthIndex(int period) {
        if (period < 1) {
            throw new IllegalArgumentException("period must be >= 1, was " + period);
        }
        this.period = period;
    }

    @Override
    public void update(Candle candle) {
        BigDecimal close = candle.getClose();

        if (previousClose == null) {
            previousClose = close;
            return;
        }

        BigDecimal change = close.subtract(previousClose);
        BigDecimal gain = change.max(BigDecimal.ZERO);
        BigDecimal loss = change.min(BigDecimal.ZERO).negate();

        if (changeCount < period) {
            // Building the initial simple average over the first `period` changes.
            avgGain = avgGain.add(gain);
            avgLoss = avgLoss.add(loss);
            changeCount++;
            if (changeCount == period) {
                avgGain = avgGain.divide(BigDecimal.valueOf(period), MC);
                avgLoss = avgLoss.divide(BigDecimal.valueOf(period), MC);
                ready = true;
            }
        } else {
            // Wilder smoothing from here on: O(1), no re-averaging over history.
            BigDecimal periodBd = BigDecimal.valueOf(period);
            avgGain = avgGain.multiply(periodBd.subtract(BigDecimal.ONE)).add(gain).divide(periodBd, MC);
            avgLoss = avgLoss.multiply(periodBd.subtract(BigDecimal.ONE)).add(loss).divide(periodBd, MC);
        }

        previousClose = close;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public Optional<BigDecimal> value() {
        if (!ready) {
            return Optional.empty();
        }
        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            return Optional.of(HUNDRED); // no losses at all in the window -> maximally overbought
        }
        BigDecimal relativeStrength = avgGain.divide(avgLoss, MC);
        return Optional.of(
                HUNDRED.subtract(HUNDRED.divide(BigDecimal.ONE.add(relativeStrength), MC))
        );
    }
}