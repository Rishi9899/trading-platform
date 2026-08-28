package com.tradingplatform.pattern;

/**
 * Market trend context for pattern validation
 */
public record TrendContext(
        TrendDirection direction,
        TrendStrength strength,
        TrendLocation location
) {
    public enum TrendDirection {
        UPTREND,
        DOWNTREND,
        SIDEWAYS
    }

    public enum TrendStrength {
        STRONG,
        MODERATE,
        WEAK
    }

    public enum TrendLocation {
        TOP,     // Near resistance
        MIDDLE,  // Mid-range
        BOTTOM   // Near support
    }
}