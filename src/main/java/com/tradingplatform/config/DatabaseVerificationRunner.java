package com.tradingplatform.config;

import com.tradingplatform.domain.strategy.Strategy;
import com.tradingplatform.domain.strategy.StrategyInstance;
import com.tradingplatform.domain.strategy.StrategyInstanceRepository;
import com.tradingplatform.domain.strategy.StrategyRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DatabaseVerificationRunner implements CommandLineRunner {

    private static final String SAMPLE_STRATEGY_NAME =
            "moving-average-crossover";

    private final StrategyRepository strategyRepository;
    private final StrategyInstanceRepository strategyInstanceRepository;

    public DatabaseVerificationRunner(
            StrategyRepository strategyRepository,
            StrategyInstanceRepository strategyInstanceRepository
    ) {
        this.strategyRepository = strategyRepository;
        this.strategyInstanceRepository = strategyInstanceRepository;
    }

    @Override
    public void run(String... args) {

        Strategy strategy = strategyRepository
                .findByName(SAMPLE_STRATEGY_NAME)
                .orElseGet(() -> {

                    Strategy created = new Strategy(
                            SAMPLE_STRATEGY_NAME,
                            "Buys when fast EMA crosses above slow EMA, " +
                                    "sells on the reverse cross."
                    );

                    return strategyRepository.save(created);
                });

        System.out.println(
                "[DB CHECK] Strategy after INSERT/lookup: " +
                        "id=" + strategy.getId() +
                        " name=" + strategy.getName()
        );

        boolean hasInstance =
                !strategyInstanceRepository
                        .findByStrategyId(strategy.getId())
                        .isEmpty();

        if (!hasInstance) {

            StrategyInstance instance =
                    new StrategyInstance(
                            strategy,
                            "NIFTY",
                            "5m",
                            "{\"fastPeriod\":9,\"slowPeriod\":20}"
                    );

            strategyInstanceRepository.save(instance);
        }

        System.out.println(
                "[DB CHECK] StrategyInstances for '" +
                        strategy.getName() + "':"
        );

        for (StrategyInstance instance :
                strategyInstanceRepository
                        .findByStrategyId(strategy.getId())) {

            System.out.println(
                    "[DB CHECK]   id=" + instance.getId()
                            + " symbol=" + instance.getSymbol()
                            + " timeframe=" + instance.getTimeframe()
                            + " status=" + instance.getStatus()
                            + " parameters=" + instance.getParameters()
            );
        }
    }
}