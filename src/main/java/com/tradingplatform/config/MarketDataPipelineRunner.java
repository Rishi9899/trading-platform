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

    // The Fyers sidecar only ever sends historical candles pre-aggregated at this
    // granularity - it is NOT the same as app.candle.timeframe-seconds (the live
    // base timeframe, typically 1m). Any derived timeframe that's a whole multiple
    // of this can be backfilled with real history by aggregating this batch;
    // anything finer than this (or not a clean multiple) simply won't have
    // historical data and will build up from live ticks only, same as before.
    private static final Duration HISTORICAL_CANDLE_TIMEFRAME = Duration.ofMinutes(5);
    private static final String HISTORICAL_CANDLE_LABEL = "5m";

    private final MarketDataProperties properties;
    private final MarketCandleRepository marketCandleRepository;
    private final SignalRepository signalRepository;
    private final StrategyInstanceLoader strategyInstanceLoader;
    private final StrategyEngine strategyEngine;

    public MarketDataPipelineRunner(MarketDataProperties properties,
                                    MarketCandleRepository marketCandleRepository,
                                    SignalRepository signalRepository,
                                    StrategyInstanceLoader strategyInstanceLoader,
                                    StrategyEngine strategyEngine) {
        this.properties = properties;
        this.marketCandleRepository = marketCandleRepository;
        this.signalRepository = signalRepository;
        this.strategyInstanceLoader = strategyInstanceLoader;
        this.strategyEngine = strategyEngine;
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

        // 3b. Which derived timeframes can be backfilled with REAL history, by
        // rolling up the 5m historical batches the sidecar sends? Only ones that
        // are a whole multiple of that 5m granularity (10m, 15m, 30m, ...) and
        // aren't 5m itself (which is already backfilled directly below).
        Map<String, Duration> backfillableDerivedTimeframes = new java.util.LinkedHashMap<>();
        for (String label : derivedTimeframeLabels) {
            if (label.equals(HISTORICAL_CANDLE_LABEL)) {
                continue;
            }
            Duration derivedTimeframe = TimeframeParser.parse(label);
            boolean isWholeMultiple = derivedTimeframe.getSeconds() > HISTORICAL_CANDLE_TIMEFRAME.getSeconds()
                    && derivedTimeframe.getSeconds() % HISTORICAL_CANDLE_TIMEFRAME.getSeconds() == 0;
            if (isWholeMultiple) {
                backfillableDerivedTimeframes.put(label, derivedTimeframe);
            } else {
                log.info("Derived timeframe {} is not a whole multiple of the {} historical candle "
                                + "granularity - it will only build up from live ticks, no historical backfill.",
                        label, HISTORICAL_CANDLE_LABEL);
            }
        }

        // 4. Hook Historical flow Multi-Chunk Aggregation
        if (tickSource instanceof FyersSidecarTickSource fyersSource) {
            // Tracks symbols actively streaming chunked historical batches
            Set<String> activeWarmups = ConcurrentHashMap.newKeySet();

            // One dedicated CandleAggregator per (symbol, derived label) purely for
            // replaying historical 5m batches into higher-timeframe candles. Kept
            // entirely separate from the live aggregators in step 3, so historical
            // replay can never corrupt a live in-progress window.
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

                    // Replace/clear history on chunk 1, append for subsequent chunks
                    boolean firstChunk = activeWarmups.add(symbol);
                    if (firstChunk) {
                        strategyEngine.replaceHistoricalCandles(symbol, HISTORICAL_CANDLE_LABEL, domainCandles);
                    } else {
                        strategyEngine.appendHistoricalCandles(symbol, HISTORICAL_CANDLE_LABEL, domainCandles);
                    }

                    // Roll the same 5m batch up into every backfillable derived timeframe
                    Map<String, HistoricalRollup> rollupsForSymbol = historicalRollups
                            .computeIfAbsent(symbol, s -> new ConcurrentHashMap<>());

                    for (Map.Entry<String, Duration> entry : backfillableDerivedTimeframes.entrySet()) {
                        String label = entry.getKey();
                        HistoricalRollup rollup = rollupsForSymbol.computeIfAbsent(label,
                                l -> new HistoricalRollup(
                                        new CandleAggregator(HISTORICAL_CANDLE_TIMEFRAME, entry.getValue(), l)));

                        // The aggregator's listener is wired once, at creation, straight to
                        // this sink - not re-added per chunk, or multi-chunk symbols would
                        // get the same aggregated candles delivered multiple times.
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
                    activeWarmups.remove(symbol); // Reset tracker for future reconnects/warmups

                    // The final in-progress bucket for each derived timeframe never got a
                    // "next candle" to trigger its flush during replay - flush it manually
                    // now that we know no more historical candles are coming.
                    Map<String, HistoricalRollup> rollupsForSymbol = historicalRollups.remove(symbol);
                    if (rollupsForSymbol != null) {
                        for (Map.Entry<String, HistoricalRollup> entry : rollupsForSymbol.entrySet()) {
                            Candle finalCandle = entry.getValue().aggregator.flushPending(symbol);
                            if (finalCandle != null) {
                                strategyEngine.appendHistoricalCandles(symbol, entry.getKey(), List.of(finalCandle));
                            }
                        }
                    }

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

    /**
     * Pairs a historical-replay CandleAggregator with the single sink its
     * listener writes into. The listener is wired once, at construction -
     * each chunk just clears the sink, feeds candles through, then reads
     * back whatever landed. Keeps a listener from ever being registered
     * more than once per aggregator across multiple historical chunks.
     */
    private static final class HistoricalRollup {
        final CandleAggregator aggregator;
        final List<Candle> sink = new ArrayList<>();

        HistoricalRollup(CandleAggregator aggregator) {
            this.aggregator = aggregator;
            this.aggregator.addListener(sink::add);
        }
    }
}