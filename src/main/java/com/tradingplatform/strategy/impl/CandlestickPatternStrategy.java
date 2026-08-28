package com.tradingplatform.strategy.impl;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.pattern.PatternMatch;
import com.tradingplatform.pattern.PatternType;
import com.tradingplatform.pattern.TrendContext;
import com.tradingplatform.pattern.service.PatternDetectionService;
import com.tradingplatform.pattern.service.TrendContextService;
import com.tradingplatform.strategy.MarketContext;
import com.tradingplatform.strategy.StrategyDecision;
import com.tradingplatform.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Trading strategy based on candlestick pattern recognition
 */
public class CandlestickPatternStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(CandlestickPatternStrategy.class);

    private final PatternDetectionService patternDetectionService;
    private final TrendContextService trendContextService;
    private final double minConfidence;
    private final boolean requireTrendContext;
    private final List<String> enabledPatterns;

    public CandlestickPatternStrategy(PatternDetectionService patternDetectionService,
                                      TrendContextService trendContextService,
                                      double minConfidence,
                                      boolean requireTrendContext,
                                      List<String> enabledPatterns) {
        this.patternDetectionService = patternDetectionService;
        this.trendContextService = trendContextService;
        this.minConfidence = minConfidence;
        this.requireTrendContext = requireTrendContext;
        this.enabledPatterns = enabledPatterns;
    }

    @Override
    public StrategyDecision evaluate(MarketContext context) {
        List<Candle> candles = context.recentCandles();

        if (candles.size() < 20) {
            return null; // Need sufficient history
        }

        // Detect patterns
        List<PatternMatch> detectedPatterns = patternDetectionService.detectPatterns(candles);

        if (detectedPatterns.isEmpty()) {
            return null;
        }

        // Filter by enabled patterns (empty list = all enabled)
        List<PatternMatch> enabledMatches = detectedPatterns.stream()
                .filter(p -> enabledPatterns.isEmpty() || enabledPatterns.contains(p.patternName()))
                .toList();

        if (enabledMatches.isEmpty()) {
            return null;
        }

        // Validate against trend context
        TrendContext trend = trendContextService.analyzeTrend(candles);

        List<PatternMatch> validPatterns = enabledMatches.stream()
                .filter(p -> !requireTrendContext || patternDetectionService.isValidInContext(p, candles))
                .filter(p -> p.confidence() >= minConfidence)
                .toList();

        if (validPatterns.isEmpty()) {
            log.debug("Patterns detected but failed context validation: {}",
                    enabledMatches.stream().map(PatternMatch::patternName).collect(Collectors.joining(", ")));
            return null;
        }

        // Get best pattern
        PatternMatch bestPattern = validPatterns.stream()
                .max((p1, p2) -> Double.compare(p1.confidence(), p2.confidence()))
                .orElse(null);

        if (bestPattern == null) {
            return null;
        }

        // Generate signal
        SignalType signalType = determineSignalType(bestPattern.type());
        Candle currentCandle = context.currentCandle();

        String reason = String.format("%s detected at %s of %s (confidence: %.1f%%, trend: %s)",
                bestPattern.patternName(),
                trend.location(),
                trend.direction(),
                bestPattern.confidence() * 100,
                trend.strength());

        log.info("Pattern signal: {} → {} ({})",
                context.symbol(), signalType, bestPattern.patternName());

        return new StrategyDecision(
                signalType,
                currentCandle.getClose(),  // ✅ Fixed - already BigDecimal
                bestPattern.confidence(),
                reason
        );
    }

    private SignalType determineSignalType(PatternType patternType) {
        return switch (patternType) {
            case BULLISH_REVERSAL -> SignalType.BUY;
            case BEARISH_REVERSAL -> SignalType.SELL;
            case CONTINUATION, NEUTRAL -> SignalType.HOLD;
        };
    }
}