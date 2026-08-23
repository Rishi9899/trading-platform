package com.tradingplatform.candle;

import java.time.Duration;

/**
 * Parses human-readable timeframe labels ("30s", "5m", "1h") into
 * Duration. Used for derived-timeframe config, and later to validate
 * StrategyInstance.timeframe values against what the pipeline actually
 * produces.
 */
public final class TimeframeParser {

    private TimeframeParser() {
    }

    public static Duration parse(String label) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Timeframe label must not be blank");
        }
        String trimmed = label.trim().toLowerCase();
        char unit = trimmed.charAt(trimmed.length() - 1);
        long amount;
        try {
            amount = Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid timeframe label: '" + label + "'. Expected e.g. '30s', '5m', '1h'.");
        }
        return switch (unit) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            default -> throw new IllegalArgumentException(
                    "Unsupported timeframe unit '" + unit + "' in '" + label + "'. Use s, m, or h.");
        };
    }
}