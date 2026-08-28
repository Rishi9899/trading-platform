package com.tradingplatform.pattern.service;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.indicator.ExponentialMovingAverage;
import com.tradingplatform.pattern.TrendContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Analyzes market trend context for pattern validation
 */
@Service
public class TrendContextService {

    private static final int TREND_EMA_PERIOD = 20;
    private static final int SUPPORT_RESISTANCE_LOOKBACK = 50;

    /**
     * Analyze trend context from recent candles
     */
    public TrendContext analyzeTrend(List<Candle> candles) {
        if (candles.size() < TREND_EMA_PERIOD) {
            return new TrendContext(
                    TrendContext.TrendDirection.SIDEWAYS,
                    TrendContext.TrendStrength.WEAK,
                    TrendContext.TrendLocation.MIDDLE
            );
        }

        TrendContext.TrendDirection direction = determineTrendDirection(candles);
        TrendContext.TrendStrength strength = determineTrendStrength(candles);
        TrendContext.TrendLocation location = determineTrendLocation(candles, direction);

        return new TrendContext(direction, strength, location);
    }

    private TrendContext.TrendDirection determineTrendDirection(List<Candle> candles) {
        // Use EMA(20) to determine trend
        ExponentialMovingAverage ema = new ExponentialMovingAverage(TREND_EMA_PERIOD);

        // Feed all candles to EMA
        for (Candle candle : candles) {
            ema.update(candle);  // ✅ Fixed: pass Candle object, not double
        }

        if (!ema.isReady()) {
            return TrendContext.TrendDirection.SIDEWAYS;
        }

        // ✅ Fixed: use value() which returns Optional<BigDecimal>
        BigDecimal emaValue = ema.value().orElse(BigDecimal.ZERO);
        Candle lastCandle = candles.get(candles.size() - 1);
        BigDecimal currentPrice = lastCandle.getClose();

        // Price significantly above EMA = uptrend
        BigDecimal upperThreshold = emaValue.multiply(BigDecimal.valueOf(1.01));
        if (currentPrice.compareTo(upperThreshold) > 0) {
            return TrendContext.TrendDirection.UPTREND;
        }

        // Price significantly below EMA = downtrend
        BigDecimal lowerThreshold = emaValue.multiply(BigDecimal.valueOf(0.99));
        if (currentPrice.compareTo(lowerThreshold) < 0) {
            return TrendContext.TrendDirection.DOWNTREND;
        }

        return TrendContext.TrendDirection.SIDEWAYS;
    }

    private TrendContext.TrendStrength determineTrendStrength(List<Candle> candles) {
        // Look at last 10 candles to determine strength
        int lookback = Math.min(10, candles.size());
        List<Candle> recentCandles = candles.subList(candles.size() - lookback, candles.size());

        int bullishCount = 0;
        int bearishCount = 0;

        for (Candle candle : recentCandles) {
            if (candle.getClose().compareTo(candle.getOpen()) > 0) {
                bullishCount++;
            } else if (candle.getClose().compareTo(candle.getOpen()) < 0) {
                bearishCount++;
            }
        }

        int dominantCount = Math.max(bullishCount, bearishCount);

        // Strong: 70%+ candles in same direction
        if (dominantCount >= lookback * 0.7) {
            return TrendContext.TrendStrength.STRONG;
        }

        // Moderate: 60%+ candles in same direction
        if (dominantCount >= lookback * 0.6) {
            return TrendContext.TrendStrength.MODERATE;
        }

        return TrendContext.TrendStrength.WEAK;
    }

    private TrendContext.TrendLocation determineTrendLocation(List<Candle> candles,
                                                              TrendContext.TrendDirection direction) {
        int lookback = Math.min(SUPPORT_RESISTANCE_LOOKBACK, candles.size());
        List<Candle> recentCandles = candles.subList(candles.size() - lookback, candles.size());

        // Find high and low of recent range
        BigDecimal high = recentCandles.stream()
                .map(Candle::getHigh)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        BigDecimal low = recentCandles.stream()
                .map(Candle::getLow)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        BigDecimal range = high.subtract(low);
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return TrendContext.TrendLocation.MIDDLE;
        }

        Candle lastCandle = candles.get(candles.size() - 1);
        BigDecimal currentPrice = lastCandle.getClose();

        // Calculate position in range (0 = bottom, 1 = top)
        BigDecimal position = currentPrice.subtract(low)
                .divide(range, 4, java.math.RoundingMode.HALF_UP);

        double positionPercent = position.doubleValue();

        // Top 20% of range
        if (positionPercent >= 0.80) {
            return TrendContext.TrendLocation.TOP;
        }

        // Bottom 20% of range
        if (positionPercent <= 0.20) {
            return TrendContext.TrendLocation.BOTTOM;
        }

        return TrendContext.TrendLocation.MIDDLE;
    }
}