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
import com.tradingplatform.marketdata.MarketDataConnectionRegistry;
import com.tradingplatform.marketdata.TickSource;
import com.tradingplatform.marketdata.fyers.FyersSidecarTickSource;
import com.tradingplatform.strategy.LoggingSignalListener;
import com.tradingplatform.strategy.PersistingSignalListener;
import com.tradingplatform.strategy.StrategyEngine;
import com.tradingplatform.ui.LiveTickStreamController;
import com.tradingplatform.ui.RedisCandleWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@EnableConfigurationProperties(MarketDataProperties.class)
public class MarketDataPipelineRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketDataPipelineRunner.class);

    private static final Duration HISTORICAL_CANDLE_TIMEFRAME = Duration.ofMinutes(5);
    private static final String HISTORICAL_CANDLE_LABEL = "5m";

    private final MarketDataProperties properties;
    private final MarketCandleRepository marketCandleRepository;
    private final SignalRepository signalRepository;
    private final StrategyInstanceLoader strategyInstanceLoader;
    private final StrategyEngine strategyEngine;

    // NEW: UI/Redis components
    private final RedisCandleWriter redisCandleWriter;
    private final LiveTickStreamController liveTickStreamController;
    private final MarketDataConnectionRegistry connectionRegistry;

    public MarketDataPipelineRunner(MarketDataProperties properties,
                                    MarketCandleRepository marketCandleRepository,
                                    SignalRepository signalRepository,
                                    StrategyInstanceLoader strategyInstanceLoader,
                                    StrategyEngine strategyEngine,
                                    RedisCandleWriter redisCandleWriter,
                                    LiveTickStreamController liveTickStreamController,
                                    MarketDataConnectionRegistry connectionRegistry) {
        this.properties = properties;
        this.marketCandleRepository = marketCandleRepository;
        this.signalRepository = signalRepository;
        this.strategyInstanceLoader = strategyInstanceLoader;
        this.strategyEngine = strategyEngine;
        this.redisCandleWriter = redisCandleWriter;
        this.liveTickStreamController = liveTickStreamController;
        this.connectionRegistry = connectionRegistry;
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

//        // 1) Signal listeners
//        strategyEngine.addSignalListener(new LoggingSignalListener());
//        strategyEngine.addSignalListener(new PersistingSignalListener(signalRepository));
//        strategyEngine.addSignalListener(new PerformanceTrackingSignalListener());

        // 1) Signal listeners
        strategyEngine.addSignalListener(new LoggingSignalListener());
        strategyEngine.addSignalListener(new PersistingSignalListener(signalRepository));
        strategyEngine.addSignalListener(new PerformanceTrackingSignalListener());

// ✅ NEW: Add signal SSE fanout
        strategyEngine.addSignalListener(signal -> {
            try {
                liveTickStreamController.onSignal(signal);
            } catch (Exception e) {
                log.warn("Failed signal SSE fanout for {}: {}", signal.getSymbol(), e.getMessage());
            }
        });

        // 2) Tick flow wiring
        tickSource.addListener(tickEventQueue);
        tickEventQueue.addListener(candleBuilder);

        // NEW: push live ticks to SSE + latest tick to Redis
        tickEventQueue.addListener(tick -> {
            try {
                long tsMillis = tick.getTimestamp().toEpochMilli();

                liveTickStreamController.onTick(
                        tick.getSymbol(),
                        tick.getPrice(),
                        tick.getVolume(),
                        tsMillis
                );

                redisCandleWriter.writeTick(
                        tick.getSymbol(),
                        tick.getPrice().doubleValue(),
                        tick.getVolume(),
                        tsMillis
                );
            } catch (Exception e) {
                log.warn("Failed UI tick fanout for {}: {}", tick.getSymbol(), e.getMessage());
            }
        });

        // 3) Base candle listeners
        candleBuilder.addListener(new LoggingCandleListener());
        candleBuilder.addListener(new CandleArchivingListener(marketCandleRepository));
        candleBuilder.addListener(strategyEngine);

        // NEW: store 1m candles to Redis
        candleBuilder.addListener(redisCandleWriter);

        // NEW: notify SSE when candle closes
        candleBuilder.addListener(candle -> {
            try {
                liveTickStreamController.onCandleClosed(
                        candle.getSymbol(),
                        Map.of(
                                "time", candle.getWindowStart().getEpochSecond(),
                                "open", candle.getOpen(),
                                "high", candle.getHigh(),
                                "low", candle.getLow(),
                                "close", candle.getClose(),
                                "volume", candle.getVolume(),
                                "timeframe", candle.getTimeframe()
                        )
                );
            } catch (Exception e) {
                log.warn("Failed candle_closed SSE fanout for {}: {}", candle.getSymbol(), e.getMessage());
            }
        });

        // 4) Derived timeframe aggregators for LIVE ticks
        for (String label : derivedTimeframeLabels) {
            Duration derivedTimeframe = TimeframeParser.parse(label);
            CandleAggregator aggregator = new CandleAggregator(baseTimeframe, derivedTimeframe, label);
            aggregator.addListener(new LoggingCandleListener());
            aggregator.addListener(strategyEngine);
            candleBuilder.addListener(aggregator);
        }

        // 5) Derived timeframes that can be backfilled from 5m history
        Map<String, Duration> backfillableDerivedTimeframes = new java.util.LinkedHashMap<>();
        for (String label : derivedTimeframeLabels) {
            if (label.equals(HISTORICAL_CANDLE_LABEL)) continue;

            Duration derivedTimeframe = TimeframeParser.parse(label);
            boolean isWholeMultiple = derivedTimeframe.getSeconds() > HISTORICAL_CANDLE_TIMEFRAME.getSeconds()
                    && derivedTimeframe.getSeconds() % HISTORICAL_CANDLE_TIMEFRAME.getSeconds() == 0;

            if (isWholeMultiple) {
                backfillableDerivedTimeframes.put(label, derivedTimeframe);
            } else {
                log.info("Derived timeframe {} is not a whole multiple of {} historical granularity; live-only.",
                        label, HISTORICAL_CANDLE_LABEL);
            }
        }

        // 6) Historical warmup flow
        if (tickSource instanceof FyersSidecarTickSource fyersSource) {
            Set<String> activeWarmups = ConcurrentHashMap.newKeySet();
            Map<String, Map<String, HistoricalRollup>> historicalRollups = new ConcurrentHashMap<>();

            fyersSource.addHistoryListener(new FyersSidecarTickSource.HistoricalDataListener() {
                @Override
                public void onHistoricalBatchReceived(String symbol, List<FyersSidecarTickSource.HistoricalCandleData> candleBatch) {
                    List<Candle> domainCandles = candleBatch.stream()
                            .map(data -> {
                                Instant windowEnd = data.timestamp().plus(HISTORICAL_CANDLE_TIMEFRAME);
                                return new Candle(
                                        symbol,
                                        HISTORICAL_CANDLE_LABEL,
                                        data.timestamp(),
                                        windowEnd,
                                        data.open(),
                                        data.high(),
                                        data.low(),
                                        data.close(),
                                        data.volume()
                                );
                            })
                            .toList();

                    boolean firstChunk = activeWarmups.add(symbol);
                    if (firstChunk) {
                        strategyEngine.replaceHistoricalCandles(symbol, HISTORICAL_CANDLE_LABEL, domainCandles);
                    } else {
                        strategyEngine.appendHistoricalCandles(symbol, HISTORICAL_CANDLE_LABEL, domainCandles);
                    }

                    Map<String, HistoricalRollup> rollupsForSymbol = historicalRollups
                            .computeIfAbsent(symbol, s -> new ConcurrentHashMap<>());

                    for (Map.Entry<String, Duration> entry : backfillableDerivedTimeframes.entrySet()) {
                        String label = entry.getKey();
                        HistoricalRollup rollup = rollupsForSymbol.computeIfAbsent(label,
                                l -> new HistoricalRollup(
                                        new CandleAggregator(HISTORICAL_CANDLE_TIMEFRAME, entry.getValue(), l)));

                        rollup.sink.clear();
                        for (Candle baseCandle : domainCandles) {
                            rollup.aggregator.onCandleClosed(baseCandle);
                        }

                        if (firstChunk) {
                            strategyEngine.replaceHistoricalCandles(symbol, label, List.copyOf(rollup.sink));
                        } else if (!rollup.sink.isEmpty()) {
                            strategyEngine.appendHistoricalCandles(symbol, label, List.copyOf(rollup.sink));
                        }
                    }
                }

                @Override
                public void onHistoryComplete(String symbol) {
                    activeWarmups.remove(symbol);

                    Map<String, HistoricalRollup> rollupsForSymbol = historicalRollups.remove(symbol);
                    if (rollupsForSymbol != null) {
                        for (Map.Entry<String, HistoricalRollup> entry : rollupsForSymbol.entrySet()) {
                            Candle finalCandle = entry.getValue().aggregator.flushPending(symbol);
                            if (finalCandle != null) {
                                strategyEngine.appendHistoricalCandles(symbol, entry.getKey(), List.of(finalCandle));
                            }
                        }
                    }

                    log.info("Historical warmup completed for {}", symbol);
                    strategyEngine.markWarmupComplete(symbol);
                }
            });
        }

        // 7) Load strategy instances
        strategyInstanceLoader.loadInto(strategyEngine, availableTimeframes);

        // 8) Start processing
        tickEventQueue.start();
        tickSource.start();
    }

    private TickSource buildTickSource(String source) {
        return switch (source) {
            case "fyers-sidecar" -> {
                var sidecar = properties.getFyers().getSidecar();
                var fyersSource = new FyersSidecarTickSource(sidecar.getUrl(), sidecar.getReconnectDelayMillis());
                connectionRegistry.registerFyersSidecar(fyersSource);
                yield fyersSource;
            }
            case "fake" -> {
                connectionRegistry.registerFake();
                yield new FakeTickGenerator(
                        properties.getTick().getSymbols(),
                        properties.getTick().getIntervalMillis()
                );
            }
            default -> throw new IllegalArgumentException("Unknown app.tick.source: " + source);
        };
    }

    private static final class HistoricalRollup {
        final CandleAggregator aggregator;
        final List<Candle> sink = new ArrayList<>();

        HistoricalRollup(CandleAggregator aggregator) {
            this.aggregator = aggregator;
            this.aggregator.addListener(sink::add);
        }
    }
}