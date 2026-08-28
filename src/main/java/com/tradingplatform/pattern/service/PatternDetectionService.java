package com.tradingplatform.pattern.service;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.pattern.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Core pattern detection service
 */
@Service
public class PatternDetectionService {

    private static final Logger log = LoggerFactory.getLogger(PatternDetectionService.class);

    private final PatternRegistry patternRegistry;
    private final TrendContextService trendContextService;

    public PatternDetectionService(PatternRegistry patternRegistry,
                                   TrendContextService trendContextService) {
        this.patternRegistry = patternRegistry;
        this.trendContextService = trendContextService;
    }

    /**
     * Detect all patterns in the candle history
     */
    public List<PatternMatch> detectPatterns(List<Candle> candles) {
        if (candles.isEmpty()) {
            return List.of();
        }

        List<PatternMatch> detectedPatterns = new ArrayList<>();
        int currentIndex = candles.size() - 1;

        // Try each registered pattern
        for (CandlestickPattern pattern : patternRegistry.getAllPatterns()) {
            try {
                PatternMatch match = pattern.detect(candles, currentIndex);
                if (match != null) {
                    detectedPatterns.add(match);
                    log.debug("Pattern detected: {} at index {} (confidence: {})",
                            match.patternName(), match.detectedAtIndex(), match.confidence());
                }
            } catch (Exception e) {
                log.error("Error detecting pattern {}: {}", pattern.getName(), e.getMessage());
            }
        }

        return detectedPatterns;
    }

    /**
     * Validate pattern against trend context
     */
    public boolean isValidInContext(PatternMatch pattern, List<Candle> candles) {
        TrendContext trend = trendContextService.analyzeTrend(candles);

        return switch (pattern.type()) {
            case BULLISH_REVERSAL -> {
                // Bullish reversal should be at bottom of downtrend
                boolean correctLocation = trend.location() == TrendContext.TrendLocation.BOTTOM;
                boolean correctTrend = trend.direction() == TrendContext.TrendDirection.DOWNTREND;
                yield correctLocation && correctTrend;
            }
            case BEARISH_REVERSAL -> {
                // Bearish reversal should be at top of uptrend
                boolean correctLocation = trend.location() == TrendContext.TrendLocation.TOP;
                boolean correctTrend = trend.direction() == TrendContext.TrendDirection.UPTREND;
                yield correctLocation && correctTrend;
            }
            case CONTINUATION -> {
                // Continuation patterns work in middle of trends
                yield trend.location() == TrendContext.TrendLocation.MIDDLE;
            }
            case NEUTRAL -> true; // Neutral patterns always valid
        };
    }
}