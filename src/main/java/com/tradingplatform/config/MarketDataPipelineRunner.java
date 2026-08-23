package com.tradingplatform.config;

import com.tradingplatform.candle.CandleAggregator;
import com.tradingplatform.candle.CandleArchivingListener;
import com.tradingplatform.candle.CandleBuilder;
import com.tradingplatform.candle.LoggingCandleListener;
import com.tradingplatform.candle.TimeframeParser;
import com.tradingplatform.domain.candle.MarketCandleRepository;
import com.tradingplatform.domain.signal.SignalRepository;
import com.tradingplatform.eventing.TickEventQueue;
import com.tradingplatform.marketdata.FakeTickGenerator;
import com.tradingplatform.marketdata.TickSource;
import com.tradingplatform.marketdata.fyers.FyersSidecarTickSource;
import com.tradingplatform.strategy.LoggingSignalListener;
import com.tradingplatform.strategy.PersistingSignalListener;
import com.tradingplatform.strategy.StrategyEngine;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

@Component
@EnableConfigurationProperties(MarketDataProperties.class)
public class MarketDataPipelineRunner implements CommandLineRunner {

    private final MarketDataProperties properties;
    private final MarketCandleRepository marketCandleRepository;
    private final SignalRepository signalRepository;
    private final StrategyInstanceLoader strategyInstanceLoader;

    public MarketDataPipelineRunner(MarketDataProperties properties,
                                    MarketCandleRepository marketCandleRepository,
                                    SignalRepository signalRepository,
                                    StrategyInstanceLoader strategyInstanceLoader) {
        this.properties = properties;
        this.marketCandleRepository = marketCandleRepository;
        this.signalRepository = signalRepository;
        this.strategyInstanceLoader = strategyInstanceLoader;
    }

    @Override
    public void run(String... args) {
        var baseTimeframeSeconds = properties.getCandle().getTimeframeSeconds();
        var derivedTimeframeLabels = properties.getCandle().getDerivedTimeframes();
        var source = properties.getTick().getSource();
        var queueCapacity = properties.getQueue().getCapacity();

        Duration baseTimeframe = Duration.ofSeconds(baseTimeframeSeconds);
        String baseLabel = baseTimeframeSeconds + "s";

        Set<String> availableTimeframes = new HashSet<>();
        availableTimeframes.add(baseLabel);
        availableTimeframes.addAll(derivedTimeframeLabels);

        System.out.println("Starting pipeline: tickSource=" + source
                + " baseTimeframe=" + baseLabel
                + " derivedTimeframes=" + derivedTimeframeLabels
                + " queueCapacity=" + queueCapacity);

        TickSource tickSource = buildTickSource(source);
        TickEventQueue tickEventQueue = new TickEventQueue(queueCapacity);
        CandleBuilder candleBuilder = new CandleBuilder(baseTimeframe, baseLabel);
        StrategyEngine strategyEngine = new StrategyEngine(50);

        strategyEngine.addSignalListener(new LoggingSignalListener());
        strategyEngine.addSignalListener(new PersistingSignalListener(signalRepository));

        tickSource.addListener(tickEventQueue);
        tickEventQueue.addListener(candleBuilder);

        candleBuilder.addListener(new LoggingCandleListener());
        candleBuilder.addListener(new CandleArchivingListener(marketCandleRepository));
        candleBuilder.addListener(strategyEngine);

        for (String label : derivedTimeframeLabels) {
            Duration derivedTimeframe = TimeframeParser.parse(label);
            CandleAggregator aggregator = new CandleAggregator(baseTimeframe, derivedTimeframe, label);
            aggregator.addListener(new LoggingCandleListener());
            aggregator.addListener(strategyEngine);
            candleBuilder.addListener(aggregator);
        }

        strategyInstanceLoader.loadInto(strategyEngine, availableTimeframes);

        tickEventQueue.start();
        tickSource.start();
    }

    private TickSource buildTickSource(String source) {
        return switch (source) {
            case "fyers-sidecar" -> {
                var sidecar = properties.getFyers().getSidecar();
                yield new FyersSidecarTickSource(sidecar.getUrl(), sidecar.getReconnectDelayMillis());
            }
            case "fake" -> new FakeTickGenerator(
                    properties.getTick().getSymbols(),
                    properties.getTick().getIntervalMillis()
            );
            default -> throw new IllegalArgumentException("Unknown app.tick.source: " + source);
        };
    }
}