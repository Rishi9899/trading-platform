
package com.tradingplatform.strategy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StrategyRegistry {

    private final Map<String, StrategyFactory> factories = new ConcurrentHashMap<>();

    public void register(String type, StrategyFactory factory) {
        factories.put(type.toLowerCase(), factory);
    }

    public TradingStrategy create(String type, Map<String, Object> parameters) {
        if (type == null) {
            throw new IllegalArgumentException("Strategy type cannot be null");
        }
        StrategyFactory factory = factories.get(type.toLowerCase());
        if (factory == null) {
            throw new IllegalArgumentException("No strategy registered for type '" + type
                    + "'. Known types: " + factories.keySet());
        }
        return factory.create(parameters);
    }
}


//package com.tradingplatform.strategy;
//
//import java.util.HashMap;
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//
//public class StrategyRegistry {
//
//    private final Map<String, StrategyFactory> factories = new HashMap<>();
//
//    public void register(String type, StrategyFactory factory) {
//        factories.put(type, factory);
//    }
//
//    public TradingStrategy create(String type, Map<String, Object> parameters) {
//        StrategyFactory factory = factories.get(type);
//        if (factory == null) {
//            throw new IllegalArgumentException("No strategy registered for type '" + type
//                    + "'. Known types: " + factories.keySet());
//        }
//        return factory.create(parameters);
//    }
//}