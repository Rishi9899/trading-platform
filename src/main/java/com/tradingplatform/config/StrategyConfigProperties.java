package com.tradingplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "app")
public class StrategyConfigProperties {

    private List<StrategyInstanceConfig> strategies = new ArrayList<>();

    public List<StrategyInstanceConfig> getStrategies() {
        return strategies;
    }

    public void setStrategies(List<StrategyInstanceConfig> strategies) {
        this.strategies = strategies;
    }

    public static class StrategyInstanceConfig {
        private String type;
        private String symbol;
        private String timeframe;
        private Map<String, Object> parameters = new HashMap<>();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public String getTimeframe() {
            return timeframe;
        }

        public void setTimeframe(String timeframe) {
            this.timeframe = timeframe;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, Object> parameters) {
            this.parameters = parameters;
        }
    }
}