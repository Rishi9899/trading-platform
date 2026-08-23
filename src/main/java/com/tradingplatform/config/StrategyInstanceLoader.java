package com.tradingplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingplatform.config.StrategyConfigProperties.StrategyInstanceConfig;
import com.tradingplatform.domain.strategy.Strategy;
import com.tradingplatform.domain.strategy.StrategyInstance;
import com.tradingplatform.domain.strategy.StrategyInstanceRepository;
import com.tradingplatform.domain.strategy.StrategyRepository;
import com.tradingplatform.strategy.StrategyEngine;
import com.tradingplatform.strategy.StrategyRegistry;
import com.tradingplatform.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Reads strategy instance definitions from YAML (app.strategies) and
 * turns each one into: a persisted Strategy + StrategyInstance row
 * (idempotent - safe to restart without duplicating), and a live
 * TradingStrategy registered into the StrategyEngine.
 *
 * YAML defines *what* strategies should run - human-editable, no
 * redeploy needed to add an instance. Postgres holds the record of each
 * configured instance (id, status, audit trail).
 */
@Component
@EnableConfigurationProperties(StrategyConfigProperties.class)
public class StrategyInstanceLoader {

    private static final Logger log = LoggerFactory.getLogger(StrategyInstanceLoader.class);

    private final StrategyConfigProperties config;
    private final StrategyRepository strategyRepository;
    private final StrategyInstanceRepository strategyInstanceRepository;
    private final StrategyRegistry strategyRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StrategyInstanceLoader(StrategyConfigProperties config,
                                  StrategyRepository strategyRepository,
                                  StrategyInstanceRepository strategyInstanceRepository,
                                  StrategyRegistry strategyRegistry) {
        this.config = config;
        this.strategyRepository = strategyRepository;
        this.strategyInstanceRepository = strategyInstanceRepository;
        this.strategyRegistry = strategyRegistry;
    }

    public void loadInto(StrategyEngine engine, Set<String> availableTimeframes) {
        for (StrategyInstanceConfig instanceConfig : config.getStrategies()) {
            if (!availableTimeframes.contains(instanceConfig.getTimeframe())) {
                log.warn("Skipping strategy config type={} symbol={} timeframe={}: "
                                + "timeframe not produced by this pipeline. Available: {}",
                        instanceConfig.getType(), instanceConfig.getSymbol(),
                        instanceConfig.getTimeframe(), availableTimeframes);
                continue;
            }

            StrategyInstance strategyInstance = findOrCreate(instanceConfig);
            TradingStrategy strategy = strategyRegistry.create(instanceConfig.getType(), instanceConfig.getParameters());

            engine.register(strategyInstance, strategy);
            log.info("Registered strategy instance id={} type={} symbol={} timeframe={}",
                    strategyInstance.getId(), instanceConfig.getType(),
                    instanceConfig.getSymbol(), instanceConfig.getTimeframe());
        }
    }

    private StrategyInstance findOrCreate(StrategyInstanceConfig instanceConfig) {
        Strategy strategy = strategyRepository.findByName(instanceConfig.getType())
                .orElseGet(() -> strategyRepository.save(new Strategy(
                        instanceConfig.getType(),
                        "Loaded from YAML config"
                )));

        return strategyInstanceRepository.findByStrategyId(strategy.getId()).stream()
                .filter(i -> i.getSymbol().equals(instanceConfig.getSymbol())
                        && i.getTimeframe().equals(instanceConfig.getTimeframe()))
                .findFirst()
                .orElseGet(() -> strategyInstanceRepository.save(new StrategyInstance(
                        strategy, instanceConfig.getSymbol(), instanceConfig.getTimeframe(),
                        toJson(instanceConfig.getParameters())
                )));
    }

    private String toJson(Map<String, Object> parameters) {
        try {
            return objectMapper.writeValueAsString(parameters);
        } catch (Exception e) {
            log.error("Failed to serialize strategy parameters, storing as empty object: {}", e.getMessage());
            return "{}";
        }
    }
}