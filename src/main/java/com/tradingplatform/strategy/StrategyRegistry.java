package com.tradingplatform.strategy;

import java.util.HashMap;
import java.util.Map;

public class StrategyRegistry {

    private final Map<String, StrategyFactory> factories = new HashMap<>();

    public void register(String type, StrategyFactory factory) {
        factories.put(type, factory);
    }

    public TradingStrategy create(String type, Map<String, Object> parameters) {
        StrategyFactory factory = factories.get(type);
        if (factory == null) {
            throw new IllegalArgumentException("No strategy registered for type '" + type
                    + "'. Known types: " + factories.keySet());
        }
        return factory.create(parameters);
    }
}