package com.tradingplatform.config;

import com.tradingplatform.candle.CandleAggregator;
import com.tradingplatform.candle.CandleArchivingListener;
import com.tradingplatform.candle.CandleBuilder;
import com.tradingplatform.candle.LoggingCandleListener;
import com.tradingplatform.candle.TimeframeParser;
import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.candle.MarketCandleRepository;
import com.tradingplatform.domain.signal.SignalRepository;
import com.tradingplatform.evaluation.PerformanceTrackingSignalListener;
import com.tradingplatform.eventing.TickEventQueue;
import com.tradingplatform.marketdata.FakeTickGenerator;
import com.tradingplatform.marketdata.TickSource;
import com.tradingplatform.marketdata.fyers.FyersSidecarTickSource;
import com.tradingplatform.strategy.LoggingSignalListener;
import com.tradingplatform.strategy.PersistingSignalListener;
import com.tradingplatform.strategy.StrategyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Component
@EnableConfigurationProperties(MarketDataProperties.class)
public class MarketDataPipelineRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketDataPipelineRunner.class);

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

        log.info("Starting pipeline: tickSource={} baseTimeframe={} derivedTimeframes={} queueCapacity={}",
                source, baseLabel, derivedTimeframeLabels, queueCapacity);

        TickSource tickSource = buildTickSource(source);
        TickEventQueue tickEventQueue = new TickEventQueue(queueCapacity);
        CandleBuilder candleBuilder = new CandleBuilder(baseTimeframe, baseLabel);
        StrategyEngine strategyEngine = new StrategyEngine(50);

        // 1. Signal listeners
        strategyEngine.addSignalListener(new LoggingSignalListener());
        strategyEngine.addSignalListener(new PersistingSignalListener(signalRepository));
        strategyEngine.addSignalListener(new PerformanceTrackingSignalListener());

        // 2. Hook live tick flow (Tick -> EventQueue -> CandleBuilder -> StrategyEngine)
        tickSource.addListener(tickEventQueue);
        tickEventQueue.addListener(candleBuilder);

        candleBuilder.addListener(new LoggingCandleListener());
        candleBuilder.addListener(new CandleArchivingListener(marketCandleRepository));
        candleBuilder.addListener(strategyEngine);

        // 3. Derived timeframe aggregators for LIVE ticks
        for (String label : derivedTimeframeLabels) {
            Duration derivedTimeframe = TimeframeParser.parse(label);
            CandleAggregator aggregator = new CandleAggregator(baseTimeframe, derivedTimeframe, label);
            aggregator.addListener(new LoggingCandleListener());
            aggregator.addListener(strategyEngine);
            candleBuilder.addListener(aggregator);
        }

        // 4. Hook Historical flow DIRECTLY to StrategyEngine (Bypasses CandleBuilder!)
        if (tickSource instanceof FyersSidecarTickSource fyersSource) {
            fyersSource.addHistoryListener(new FyersSidecarTickSource.HistoricalDataListener() {
                @Override
                public void onHistoricalCandle(String symbol, Instant timestamp, BigDecimal open,
                                               BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
                    // Reconstruct 5-minute historical candle (matches sidecar resolution)
                    Instant windowEnd = timestamp.plus(Duration.ofMinutes(5));
                    Candle historicalCandle = new Candle(symbol, "5m", timestamp, windowEnd, open, high, low, close, volume);

                    // Seed indicator history directly without firing strategy evaluations
                    strategyEngine.seedHistoricalCandle(historicalCandle);
                }

                @Override
                public void onHistoryComplete(String symbol) {
                    log.info("Historical warmup completed for {}. Indicators primed and ready for live market open.", symbol);
                    strategyEngine.markWarmupComplete(symbol);
                }
            });
        }

        // 5. Load strategy instances
        strategyInstanceLoader.loadInto(strategyEngine, availableTimeframes);

        // 6. Start processing
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