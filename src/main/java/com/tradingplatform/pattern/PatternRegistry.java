package com.tradingplatform.pattern;

import com.tradingplatform.pattern.impl.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Central registry of all candlestick patterns
 */
@Component
public class PatternRegistry {

    private final List<CandlestickPattern> patterns;

    public PatternRegistry() {
        this.patterns = new ArrayList<>();
        registerDefaultPatterns();
    }

    private void registerDefaultPatterns() {
        // Bullish patterns
        patterns.add(new BullishEngulfingPattern());
        patterns.add(new HammerPattern());
        patterns.add(new MorningStarPattern());

        // Bearish patterns
        patterns.add(new BearishEngulfingPattern());
        patterns.add(new EveningStarPattern());
        patterns.add(new ShootingStarPattern());
    }

    public List<CandlestickPattern> getAllPatterns() {
        return new ArrayList<>(patterns);
    }

    public List<CandlestickPattern> getPatternsByType(PatternType type) {
        return patterns.stream()
                .filter(p -> p.getType() == type)
                .toList();
    }

    public CandlestickPattern getPattern(String name) {
        return patterns.stream()
                .filter(p -> p.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }
}