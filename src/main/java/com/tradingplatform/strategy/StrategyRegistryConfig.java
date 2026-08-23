package com.tradingplatform.strategy;

import com.tradingplatform.strategy.impl.CandleDirectionStrategy;
import com.tradingplatform.strategy.impl.EmaCrossoverStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.Map;

@Configuration
public class StrategyRegistryConfig {

    @Bean
    public StrategyRegistry strategyRegistry() {
        StrategyRegistry registry = new StrategyRegistry();

        registry.register("candle-direction", parameters -> new CandleDirectionStrategy());

        registry.register("ema-crossover", parameters -> new EmaCrossoverStrategy(
                intParam(parameters, "fastPeriod", 9),
                intParam(parameters, "slowPeriod", 20),
                intParam(parameters, "rsiPeriod", 14),
                bigDecimalParam(parameters, "buyRsiThreshold", "55"),
                bigDecimalParam(parameters, "sellRsiThreshold", "45")
        ));

        return registry;
    }

    private static int intParam(Map<String, Object> parameters, String key, int defaultValue) {
        Object value = parameters.get(key);
        if (value == null) {
            return defaultValue;
        }
        return Integer.parseInt(value.toString());
    }

    private static BigDecimal bigDecimalParam(Map<String, Object> parameters, String key, String defaultValue) {
        Object value = parameters.get(key);
        return new BigDecimal(value != null ? value.toString() : defaultValue);
    }
}