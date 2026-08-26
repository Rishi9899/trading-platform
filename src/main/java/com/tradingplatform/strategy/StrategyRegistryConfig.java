package com.tradingplatform.strategy;

import com.tradingplatform.strategy.impl.BollingerBreakoutStrategy;
import com.tradingplatform.strategy.impl.CandleDirectionStrategy;
import com.tradingplatform.strategy.impl.EmaCrossoverStrategy;
import com.tradingplatform.strategy.impl.MacdMomentumStrategy;
import com.tradingplatform.strategy.impl.TrendConfirmedEmaCrossoverStrategy;
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
                intParam(parameters, "slowPeriod", 21),
                intParam(parameters, "rsiPeriod", 14),
                bigDecimalParam(parameters, "buyRsiThreshold", "50.0"),
                bigDecimalParam(parameters, "sellRsiThreshold", "50.0")
        ));

        registry.register("macd-momentum", parameters -> new MacdMomentumStrategy(
                intParam(parameters, "fastPeriod", 12),
                intParam(parameters, "slowPeriod", 26),
                intParam(parameters, "signalPeriod", 9),
                bigDecimalParam(parameters, "histogramScale", "0.5")
        ));

        registry.register("bollinger-breakout", parameters -> new BollingerBreakoutStrategy(
                intParam(parameters, "bandPeriod", 20),
                bigDecimalParam(parameters, "stdDevMultiplier", "2.0"),
                intParam(parameters, "atrPeriod", 14),
                bigDecimalParam(parameters, "minBandWidthAtrMultiple", "1.5")
        ));

        registry.register("ema-crossover-mtf", parameters -> new TrendConfirmedEmaCrossoverStrategy(
                intParam(parameters, "fastPeriod", 9),
                intParam(parameters, "slowPeriod", 21),
                intParam(parameters, "rsiPeriod", 14),
                bigDecimalParam(parameters, "buyRsiThreshold", "50.0"),
                bigDecimalParam(parameters, "sellRsiThreshold", "50.0"),
                intParam(parameters, "trendPeriod", 10)
        ));

        return registry;
    }

    private static int intParam(Map<String, Object> parameters, String key, int defaultValue) {
        if (parameters == null) return defaultValue;
        Object value = parameters.get(key);
        if (value instanceof Number number) return number.intValue();
        return value != null ? Integer.parseInt(value.toString()) : defaultValue;
    }

    private static BigDecimal bigDecimalParam(Map<String, Object> parameters, String key, String defaultValue) {
        if (parameters == null) return new BigDecimal(defaultValue);
        Object value = parameters.get(key);
        return new BigDecimal(value != null ? value.toString() : defaultValue);
    }
}