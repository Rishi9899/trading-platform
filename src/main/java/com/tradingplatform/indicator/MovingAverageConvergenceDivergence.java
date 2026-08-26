package com.tradingplatform.indicator;

import com.tradingplatform.domain.candle.Candle;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * MACD: fastEma - slowEma is the MACD line; an EMA of the MACD line is
 * the signal line; their difference is the histogram. Built entirely
 * from the existing ExponentialMovingAverage building block, so it
 * inherits its O(1)-per-update characteristics for free.
 *
 * Not ready until the slow EMA is seeded AND enough MACD-line values
 * have flowed into the signal EMA - i.e. isReady() lags emaSlow.isReady()
 * by up to signalPeriod candles.
 */
public class MovingAverageConvergenceDivergence implements Indicator<MovingAverageConvergenceDivergence.MacdValue> {

    public record MacdValue(BigDecimal macdLine, BigDecimal signalLine, BigDecimal histogram) {
    }

    private final ExponentialMovingAverage emaFast;
    private final ExponentialMovingAverage emaSlow;
    private final ExponentialMovingAverage signalEma;

    public MovingAverageConvergenceDivergence(int fastPeriod, int slowPeriod, int signalPeriod) {
        if (fastPeriod >= slowPeriod) {
            throw new IllegalArgumentException(
                    "fastPeriod (" + fastPeriod + ") must be less than slowPeriod (" + slowPeriod + ")");
        }
        this.emaFast = new ExponentialMovingAverage(fastPeriod);
        this.emaSlow = new ExponentialMovingAverage(slowPeriod);
        this.signalEma = new ExponentialMovingAverage(signalPeriod);
    }

    public MovingAverageConvergenceDivergence() {
        this(12, 26, 9);
    }

    @Override
    public void update(Candle candle) {
        emaFast.update(candle);
        emaSlow.update(candle);

        if (!emaFast.isReady() || !emaSlow.isReady()) {
            return;
        }

        BigDecimal macdLine = emaFast.value().orElseThrow().subtract(emaSlow.value().orElseThrow());
        // Feed the MACD line itself through an EMA to get the signal line -
        // the signal EMA only ever sees synthetic "candles" wrapping this value.
        signalEma.update(syntheticCandle(candle, macdLine));
    }

    @Override
    public boolean isReady() {
        return signalEma.isReady();
    }

    @Override
    public Optional<MacdValue> value() {
        if (!isReady()) {
            return Optional.empty();
        }
        BigDecimal macdLine = emaFast.value().orElseThrow().subtract(emaSlow.value().orElseThrow());
        BigDecimal signalLine = signalEma.value().orElseThrow();
        return Optional.of(new MacdValue(macdLine, signalLine, macdLine.subtract(signalLine)));
    }

    private static Candle syntheticCandle(Candle source, BigDecimal close) {
        return new Candle(source.getSymbol(), source.getTimeframe(), source.getWindowStart(), source.getWindowEnd(),
                close, close, close, close, 0L);
    }
}
