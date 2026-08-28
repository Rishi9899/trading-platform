package com.tradingplatform.pattern;

import com.tradingplatform.domain.candle.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Base class with helper methods for pattern detection
 * Handles BigDecimal arithmetic properly
 */
public abstract class BasePattern implements CandlestickPattern {

    protected boolean isBullish(Candle c) {
        return c.getClose().compareTo(c.getOpen()) > 0;
    }

    protected boolean isBearish(Candle c) {
        return c.getClose().compareTo(c.getOpen()) < 0;
    }

    protected BigDecimal getBody(Candle c) {
        return c.getClose().subtract(c.getOpen()).abs();
    }

    protected BigDecimal getUpperShadow(Candle c) {
        BigDecimal topOfBody = c.getOpen().max(c.getClose());
        return c.getHigh().subtract(topOfBody);
    }

    protected BigDecimal getLowerShadow(Candle c) {
        BigDecimal bottomOfBody = c.getOpen().min(c.getClose());
        return bottomOfBody.subtract(c.getLow());
    }

    protected BigDecimal getTotalRange(Candle c) {
        return c.getHigh().subtract(c.getLow());
    }

    protected double getBodyPercent(Candle c) {
        BigDecimal range = getTotalRange(c);
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return 0;
        }
        return getBody(c)
                .divide(range, 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    protected boolean isSmallBody(Candle c) {
        return getBodyPercent(c) < 0.3;
    }

    protected boolean hasLongLowerShadow(Candle c) {
        BigDecimal body = getBody(c);
        BigDecimal lowerShadow = getLowerShadow(c);

        if (body.compareTo(BigDecimal.ZERO) == 0) {
            return lowerShadow.compareTo(BigDecimal.ZERO) > 0;
        }

        return lowerShadow.compareTo(body.multiply(BigDecimal.valueOf(2))) > 0;
    }

    protected boolean hasLongUpperShadow(Candle c) {
        BigDecimal body = getBody(c);
        BigDecimal upperShadow = getUpperShadow(c);

        if (body.compareTo(BigDecimal.ZERO) == 0) {
            return upperShadow.compareTo(BigDecimal.ZERO) > 0;
        }

        return upperShadow.compareTo(body.multiply(BigDecimal.valueOf(2))) > 0;
    }

    /**
     * Compare two BigDecimal values safely
     */
    protected boolean isGreater(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0;
    }

    protected boolean isLess(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) < 0;
    }

    protected boolean isEqual(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) == 0;
    }

    /**
     * Calculate ratio as double for confidence calculations
     */
    protected double ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.compareTo(BigDecimal.ZERO) == 0) {
            return 0;
        }
        return numerator
                .divide(denominator, 4, RoundingMode.HALF_UP)
                .doubleValue();
    }
}