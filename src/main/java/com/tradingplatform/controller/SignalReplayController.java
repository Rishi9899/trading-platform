package com.tradingplatform.controller;

import com.tradingplatform.config.MarketDataProperties;
import com.tradingplatform.config.StrategyConfigProperties;
import com.tradingplatform.config.StrategyConfigProperties.StrategyInstanceConfig;
import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.evaluation.StrategyPerformance;
import com.tradingplatform.strategy.MarketContext;
import com.tradingplatform.strategy.StrategyDecision;
import com.tradingplatform.strategy.StrategyEngine;
import com.tradingplatform.strategy.StrategyRegistry;
import com.tradingplatform.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Serves the UI's market catalog (symbols, per-market strategy config from
 * application.yml) and the signal-replay/backtest endpoints. Consolidated
 * from what used to be a separate SymbolController - if you're looking for
 * /symbols or /strategy-config and don't see them, they're here now.
 */
@RestController
@RequestMapping("/ui/api")
public class SignalReplayController {

    private static final Logger log = LoggerFactory.getLogger(SignalReplayController.class);

    // Every strategy type StrategyRegistryConfig registers. Kept here (not
    // reflected from the registry) so "replay every strategy" has a stable,
    // explicit list independent of registration order.
    private static final List<String> ALL_STRATEGY_TYPES = List.of(
            "candle-direction", "ema-crossover", "ema-crossover-mtf",
            "macd-momentum", "bollinger-breakout", "donchian-breakout", "candlestick-pattern"
    );

    private final MarketDataProperties marketDataProperties;
    private final StrategyConfigProperties strategyConfigProperties;
    private final StrategyEngine strategyEngine;
    private final StrategyRegistry strategyRegistry;

    public SignalReplayController(MarketDataProperties marketDataProperties,
                                  StrategyConfigProperties strategyConfigProperties,
                                  StrategyEngine strategyEngine,
                                  StrategyRegistry strategyRegistry) {
        this.marketDataProperties = marketDataProperties;
        this.strategyConfigProperties = strategyConfigProperties;
        this.strategyEngine = strategyEngine;
        this.strategyRegistry = strategyRegistry;
    }

    // ==========================================
    // Symbol & Configuration Endpoints
    // ==========================================

    @GetMapping("/symbols")
    public List<String> getSymbols() {
        return marketDataProperties.getTick().getSymbols();
    }

    /**
     * What's actually configured in app.strategies (application.yml) for a
     * given market - the same data resolveParameters() below reads from.
     * Omit "symbol" to see every configured strategy instance across every market.
     */
    @GetMapping("/strategy-config")
    public List<StrategyConfigDTO> getStrategyConfig(@RequestParam(required = false) String symbol) {
        return strategyConfigProperties.getStrategies().stream()
                .filter(cfg -> symbol == null || cfg.getSymbol().equalsIgnoreCase(symbol))
                .map(StrategyConfigDTO::new)
                .toList();
    }

    // ==========================================
    // Signal Replay Endpoints
    // ==========================================

    @GetMapping("/signals/replay")
    public ReplayResultDTO replaySignals(
            @RequestParam String symbol,
            @RequestParam(required = false) String strategy,
            @RequestParam(defaultValue = "5m") String timeframe) {

        ReplayResultDTO result = new ReplayResultDTO();
        result.symbol = symbol;
        result.timeframe = timeframe;
        result.signals = new ArrayList<>();

        List<Candle> candles = strategyEngine.getHistoricalCandles(symbol, timeframe);
        result.candleCount = candles.size();

        if (candles.isEmpty()) {
            result.message = "No warmup candles found in memory for " + symbol + "/" + timeframe;
            return result;
        }

        List<StrategyReplayEntry> strategies = getStrategiesToReplay(symbol, timeframe, strategy);

        // What was actually used to build each strategy - check this first if
        // replay results don't move after a yml edit.
        result.strategiesUsed = new ArrayList<>();
        for (StrategyReplayEntry entry : strategies) {
            ResolvedStrategyDTO used = new ResolvedStrategyDTO();
            used.type = entry.type();
            used.parameters = entry.resolved().parameters();
            used.parameterSource = entry.resolved().source();
            result.strategiesUsed.add(used);
        }

        for (StrategyReplayEntry entry : strategies) {
            TradingStrategy tradingStrategy = entry.strategy();
            String strategyName = entry.type();
            List<Candle> history = new ArrayList<>();

            for (int i = 0; i < candles.size(); i++) {
                Candle candle = candles.get(i);
                history.add(candle);

                if (history.size() < 50) continue;

                MarketContext context = new MarketContext(symbol, candle, new ArrayList<>(history));

                try {
                    StrategyDecision decision = tradingStrategy.evaluate(context);

                    if (decision != null && decision.signalType() != SignalType.HOLD) {
                        ReplaySignalDTO signal = new ReplaySignalDTO();
                        signal.timestamp = candle.getWindowStart();
                        signal.candleIndex = i;
                        signal.strategyType = strategyName;
                        signal.signalType = decision.signalType();
                        signal.price = candle.getClose();
                        signal.confidence = decision.confidence();
                        signal.reason = decision.reason();
                        signal.candleData = new CandleDataDTO(candle);
                        result.signals.add(signal);
                    }
                } catch (Exception e) {
                    // Skip
                }
            }
        }

        result.signals.sort((a, b) -> b.timestamp.compareTo(a.timestamp));
        result.buySignals = (int) result.signals.stream().filter(s -> s.signalType == SignalType.BUY).count();
        result.sellSignals = (int) result.signals.stream().filter(s -> s.signalType == SignalType.SELL).count();
        result.message = "Found " + result.signals.size() + " actionable signals in " + candles.size() + " warmup candles";
        result.performance = computePotentialProfit(result.signals);

        return result;
    }

    @GetMapping("/signals/replay/candle")
    public CandleReplayDTO replayCandle(
            @RequestParam String symbol,
            @RequestParam int index,
            @RequestParam(defaultValue = "5m") String timeframe) {

        CandleReplayDTO result = new CandleReplayDTO();
        result.symbol = symbol;
        result.timeframe = timeframe;
        result.candleIndex = index;
        result.signals = new ArrayList<>();

        List<Candle> candles = strategyEngine.getHistoricalCandles(symbol, timeframe);

        if (candles.isEmpty()) {
            result.message = "No warmup candles found";
            return result;
        }

        if (index >= candles.size() || index < 50) {
            result.message = "Invalid index. Must be between 50 and " + (candles.size() - 1);
            return result;
        }

        List<Candle> history = candles.subList(0, index + 1);
        Candle targetCandle = candles.get(index);
        result.targetTime = targetCandle.getWindowStart();
        result.candleData = new CandleDataDTO(targetCandle);

        MarketContext context = new MarketContext(symbol, targetCandle, new ArrayList<>(history));
        List<StrategyReplayEntry> strategies = getStrategiesToReplay(symbol, timeframe, null);

        for (StrategyReplayEntry entry : strategies) {
            TradingStrategy strategy = entry.strategy();
            String strategyName = entry.type();

            try {
                StrategyDecision decision = strategy.evaluate(context);

                if (decision != null) {
                    ReplaySignalDTO signal = new ReplaySignalDTO();
                    signal.timestamp = targetCandle.getWindowStart();
                    signal.candleIndex = index;
                    signal.strategyType = strategyName;
                    signal.signalType = decision.signalType();
                    signal.price = targetCandle.getClose();
                    signal.confidence = decision.confidence();
                    signal.reason = decision.reason();
                    result.signals.add(signal);
                } else {
                    ReplaySignalDTO signal = new ReplaySignalDTO();
                    signal.strategyType = strategyName;
                    signal.signalType = SignalType.HOLD;
                    signal.reason = "Warming up";
                    result.signals.add(signal);
                }
            } catch (Exception e) {
                ReplaySignalDTO error = new ReplaySignalDTO();
                error.strategyType = strategyName;
                error.signalType = SignalType.HOLD;
                error.reason = "Error: " + e.getMessage();
                result.signals.add(error);
            }
        }

        result.buyVotes = (int) result.signals.stream().filter(s -> s.signalType == SignalType.BUY).count();
        result.sellVotes = (int) result.signals.stream().filter(s -> s.signalType == SignalType.SELL).count();
        result.holdVotes = (int) result.signals.stream().filter(s -> s.signalType == SignalType.HOLD).count();

        return result;
    }

    @GetMapping("/signals/replay/patterns")
    public ReplayResultDTO replayPatterns(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "5m") String timeframe) {

        // Candlestick-pattern replay is just the general replay pinned to the
        // "candlestick-pattern" strategy - same signal generation and P&L
        // simulation path as every other strategy, so it stays correct as
        // that logic evolves instead of drifting out of sync as a stub.
        return replaySignals(symbol, "candlestick-pattern", timeframe);
    }

    @GetMapping("/signals/replay/warmup-info")
    public WarmupInfoDTO getWarmupInfo(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "5m") String timeframe) {

        WarmupInfoDTO info = new WarmupInfoDTO();
        info.symbol = symbol;
        info.timeframe = timeframe;

        List<Candle> candles = strategyEngine.getHistoricalCandles(symbol, timeframe);
        info.candleCount = candles.size();
        info.warmupComplete = strategyEngine.isWarmupComplete(symbol);

        if (!candles.isEmpty()) {
            info.firstCandleTime = candles.get(0).getWindowStart();
            info.lastCandleTime = candles.get(candles.size() - 1).getWindowStart();
        }

        return info;
    }

    // ==========================================
    // Helper Methods & Records
    // ==========================================

    /**
     * Builds strategies for replay through the exact same path live trading
     * uses - StrategyRegistry.create(type, parameters) - instead of a
     * separate hardcoded copy, so changing app.strategies in application.yml
     * actually changes what replay simulates.
     *
     * Parameter resolution order per type:
     *   1. The app.strategies entry matching this exact type+symbol+timeframe.
     *   2. Any other app.strategies entry of the same type (different symbol).
     *   3. The strategy factory's own built-in defaults (StrategyRegistryConfig).
     */
    private List<StrategyReplayEntry> getStrategiesToReplay(String symbol, String timeframe, String strategyFilter) {
        List<String> types = strategyFilter != null ? List.of(strategyFilter) : ALL_STRATEGY_TYPES;
        List<StrategyReplayEntry> strategies = new ArrayList<>();

        for (String type : types) {
            try {
                ResolvedParameters resolved = resolveParameters(type, symbol, timeframe);
                strategies.add(new StrategyReplayEntry(
                        type, strategyRegistry.create(type, resolved.parameters()), resolved));
            } catch (IllegalArgumentException e) {
                // Unknown/unregistered strategy type (e.g. a bad ?strategy= filter) - skip rather than 500.
            }
        }

        return strategies;
    }

    private ResolvedParameters resolveParameters(String type, String symbol, String timeframe) {
        List<StrategyInstanceConfig> configs = strategyConfigProperties.getStrategies();
        // If this logs 0, the app hasn't picked up app.strategies at all - almost
        // always a stale build or a JVM that was never restarted after the yml edit.
        log.debug("resolveParameters: {} yml strategy entries loaded for type={} symbol={} timeframe={}",
                configs.size(), type, symbol, timeframe);

        for (StrategyInstanceConfig cfg : configs) {
            if (cfg.getType().equalsIgnoreCase(type)
                    && cfg.getSymbol().equalsIgnoreCase(symbol)
                    && cfg.getTimeframe().equalsIgnoreCase(timeframe)) {
                return new ResolvedParameters(cfg.getParameters(), "exact-match (yml: " + cfg.getSymbol() + "/" + cfg.getTimeframe() + ")");
            }
        }
        for (StrategyInstanceConfig cfg : configs) {
            if (cfg.getType().equalsIgnoreCase(type)) {
                return new ResolvedParameters(cfg.getParameters(), "type-fallback (yml: " + cfg.getSymbol() + "/" + cfg.getTimeframe() + ", not this symbol/timeframe)");
            }
        }
        return new ResolvedParameters(Map.of(), "factory-default (no yml entry found for this type at all)");
    }

    /**
     * Walks the replayed signals in chronological order and simulates
     * flip/flatten trades exactly like the live StrategyEvaluator does: the
     * first BUY or SELL opens a position; a same-direction signal while a
     * position is open is ignored (no pyramiding); an opposite signal closes
     * it flat (it does not immediately reopen in the new direction - that
     * needs its own subsequent signal, same as live).
     *
     * Figures are in raw price points (exit - entry for BUY, entry - exit
     * for SELL), NOT currency - TradeSimulator has no quantity/lot-size
     * input yet, matching the rest of the platform's performance tracking.
     */
    private PerformanceSummaryDTO computePotentialProfit(List<ReplaySignalDTO> signals) {
        // result.signals is sorted most-recent-first for display; simulation
        // needs chronological order, so sort a copy rather than mutate that.
        List<ReplaySignalDTO> chronological = new ArrayList<>(signals);
        chronological.sort(Comparator.comparing(s -> s.timestamp));

        StrategyPerformance performance = new StrategyPerformance(0L); // synthetic id - not a persisted strategy instance
        ReplaySignalDTO openPosition = null;

        for (ReplaySignalDTO signal : chronological) {
            if (signal.signalType == null || signal.signalType == SignalType.HOLD) continue;

            BigDecimal price = asBigDecimal(signal.price);
            if (price == null) continue;

            if (openPosition == null) {
                openPosition = signal; // enter
            } else if (openPosition.signalType == signal.signalType) {
                // same direction while already in a position - ignore, matches StrategyEvaluator
            } else {
                BigDecimal entryPrice = asBigDecimal(openPosition.price);
                if (entryPrice != null) {
                    BigDecimal pnl = openPosition.signalType == SignalType.BUY
                            ? price.subtract(entryPrice)
                            : entryPrice.subtract(price);
                    performance.recordTrade(pnl);
                }
                openPosition = null; // flat - a later signal is needed to open the opposite side
            }
        }

        PerformanceSummaryDTO dto = new PerformanceSummaryDTO();
        dto.totalTrades = performance.getTotalTrades();
        dto.winningTrades = performance.getWinningTrades();
        dto.losingTrades = performance.getLosingTrades();
        dto.winRatePercent = performance.getWinRate();
        dto.netProfitPoints = performance.getNetProfit();
        dto.grossProfitPoints = performance.getGrossProfit();
        dto.grossLossPoints = performance.getGrossLoss();
        dto.profitFactor = performance.getProfitFactor();
        dto.maxDrawdownPoints = performance.getMaxDrawdown();
        dto.expectancyPoints = performance.getExpectancy();
        dto.averageWinPoints = performance.getAverageWin();
        dto.averageLossPoints = performance.getAverageLoss();
        dto.rewardToRiskRatio = performance.getRewardToRiskRatio();
        dto.hasUnclosedPosition = openPosition != null;
        dto.note = "Points, not currency - TradeSimulator has no quantity/lot-size input yet. "
                + "Multiply expectancy/net-profit by an assumed position size for a currency estimate."
                + (dto.hasUnclosedPosition ? " Last signal opened a position with no closing signal yet in this "
                                             + "window, so it's excluded from the totals above." : "");
        return dto;
    }

    private BigDecimal asBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private record ResolvedParameters(Map<String, Object> parameters, String source) {}

    private record StrategyReplayEntry(String type, TradingStrategy strategy, ResolvedParameters resolved) {}

    // ==========================================
    // DTO Classes
    // ==========================================

    public static class StrategyConfigDTO {
        public String type;
        public String symbol;
        public String timeframe;
        public String confirmationTimeframe;
        public Map<String, Object> parameters;

        public StrategyConfigDTO(StrategyInstanceConfig cfg) {
            this.type = cfg.getType();
            this.symbol = cfg.getSymbol();
            this.timeframe = cfg.getTimeframe();
            this.confirmationTimeframe = cfg.getConfirmationTimeframe();
            this.parameters = cfg.getParameters();
        }
    }

    public static class ReplayResultDTO {
        public String symbol;
        public String timeframe;
        public int candleCount;
        public int buySignals;
        public int sellSignals;
        public List<ReplaySignalDTO> signals;
        public String message;
        public PerformanceSummaryDTO performance;
        /** What was actually used to build each strategy - check this first if replay results don't move after a yml edit. */
        public List<ResolvedStrategyDTO> strategiesUsed;
    }

    public static class ResolvedStrategyDTO {
        public String type;
        public Map<String, Object> parameters;
        /** "exact-match" = this symbol+timeframe's own yml entry; "type-fallback" = borrowed from another symbol; "factory-default" = no yml entry exists for this type at all. */
        public String parameterSource;
    }

    public static class CandleReplayDTO {
        public String symbol;
        public String timeframe;
        public int candleIndex;
        public Instant targetTime;
        public CandleDataDTO candleData;
        public int buyVotes;
        public int sellVotes;
        public int holdVotes;
        public List<ReplaySignalDTO> signals;
        public String message;
    }

    public static class ReplaySignalDTO {
        public Instant timestamp;
        public Integer candleIndex;
        public String strategyType;
        public SignalType signalType;
        public Object price;
        public Double confidence;
        public String reason;
        public CandleDataDTO candleData;
    }

    public static class CandleDataDTO {
        public Instant time;
        public Object open;
        public Object high;
        public Object low;
        public Object close;
        public Long volume;

        public CandleDataDTO(Candle candle) {
            this.time = candle.getWindowStart();
            this.open = candle.getOpen();
            this.high = candle.getHigh();
            this.low = candle.getLow();
            this.close = candle.getClose();
            this.volume = candle.getVolume();
        }
    }

    public static class WarmupInfoDTO {
        public String symbol;
        public String timeframe;
        public int candleCount;
        public boolean warmupComplete;
        public Instant firstCandleTime;
        public Instant lastCandleTime;
    }

    public static class PerformanceSummaryDTO {
        public int totalTrades;
        public int winningTrades;
        public int losingTrades;
        public double winRatePercent;
        public BigDecimal netProfitPoints;
        public BigDecimal grossProfitPoints;
        public BigDecimal grossLossPoints;
        public BigDecimal profitFactor;
        public BigDecimal maxDrawdownPoints;
        public BigDecimal expectancyPoints;
        public BigDecimal averageWinPoints;
        public BigDecimal averageLossPoints;
        public BigDecimal rewardToRiskRatio;
        public boolean hasUnclosedPosition;
        public String note;
    }
}