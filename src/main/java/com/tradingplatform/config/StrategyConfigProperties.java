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
        private String confirmationTimeframe;
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

        /**
         * Optional coarser timeframe (e.g. "15m" for a "5m" strategy) this
         * instance can read for trend confirmation. Purely additive context -
         * does not trigger evaluation on its own, and is not persisted on the
         * StrategyInstance entity since it's engine wiring, not instance state.
         */
        public String getConfirmationTimeframe() {
            return confirmationTimeframe;
        }

        public void setConfirmationTimeframe(String confirmationTimeframe) {
            this.confirmationTimeframe = confirmationTimeframe;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, Object> parameters) {
            this.parameters = parameters;
        }
    }
}