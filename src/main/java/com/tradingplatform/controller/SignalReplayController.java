package com.tradingplatform.controller;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.evaluation.StrategyPerformance;
import com.tradingplatform.pattern.service.PatternDetectionService;
import com.tradingplatform.pattern.service.TrendContextService;
import com.tradingplatform.strategy.MarketContext;
import com.tradingplatform.strategy.StrategyDecision;
import com.tradingplatform.strategy.StrategyEngine;
import com.tradingplatform.strategy.TradingStrategy;
import com.tradingplatform.strategy.impl.*;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/ui/api/signals/replay")
public class SignalReplayController {

    private final StrategyEngine strategyEngine;
    private final PatternDetectionService patternDetectionService;
    private final TrendContextService trendContextService;

    public SignalReplayController(StrategyEngine strategyEngine,
                                  PatternDetectionService patternDetectionService,
                                  TrendContextService trendContextService) {
        this.strategyEngine = strategyEngine;
        this.patternDetectionService = patternDetectionService;
        this.trendContextService = trendContextService;
    }

    @GetMapping
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

        List<TradingStrategy> strategies = getStrategiesToReplay(strategy);

        for (TradingStrategy tradingStrategy : strategies) {
            String strategyName = getStrategyName(tradingStrategy);
            List<Candle> history = new ArrayList<>();

            for (int i = 0; i < candles.size(); i++) {
                Candle candle = candles.get(i);
                history.add(candle);

                if (history.size() < 50) continue;

                // ✅ FIXED: Pass candle and history correctly
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

    @GetMapping("/candle")
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

        // ✅ FIXED: Pass candle and history correctly
        MarketContext context = new MarketContext(symbol, targetCandle, new ArrayList<>(history));
        List<TradingStrategy> strategies = getStrategiesToReplay(null);

        for (TradingStrategy strategy : strategies) {
            String strategyName = getStrategyName(strategy);

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
                    // Strategy not ready yet
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

    @GetMapping("/patterns")
    public ReplayResultDTO replayPatterns(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "5m") String timeframe) {

        // Candlestick-pattern replay is just the general replay pinned to the
        // "candlestick-pattern" strategy - same signal generation and P&L
        // simulation path as every other strategy, so it stays correct as
        // that logic evolves instead of drifting out of sync as a stub.
        return replaySignals(symbol, "candlestick-pattern", timeframe);
    }

    @GetMapping("/warmup-info")
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

    // ✅ FIXED: Use correct constructor parameters based on actual strategy implementations
    private List<TradingStrategy> getStrategiesToReplay(String strategyFilter) {
        List<TradingStrategy> strategies = new ArrayList<>();

        if (strategyFilter == null || strategyFilter.equals("ema-crossover")) {
            strategies.add(new EmaCrossoverStrategy(
                    9, 21, 14,
                    new BigDecimal("50.0"),
                    new BigDecimal("50.0")
            ));
        }
        if (strategyFilter == null || strategyFilter.equals("macd-momentum")) {
            strategies.add(new MacdMomentumStrategy(
                    12, 26, 9,
                    new BigDecimal("0.5")
            ));
        }
        if (strategyFilter == null || strategyFilter.equals("bollinger-breakout")) {
            strategies.add(new BollingerBreakoutStrategy(
                    20,
                    new BigDecimal("2.0"),
                    14,
                    new BigDecimal("1.5")
            ));
        }
        if (strategyFilter == null || strategyFilter.equals("donchian-breakout")) {
            strategies.add(new DonchianBreakoutStrategy(
                    55, 20, 20, 0.5
            ));
        }
        if (strategyFilter == null || strategyFilter.equals("candlestick-pattern")) {
            // Same defaults as the live registration in StrategyRegistryConfig
            // (minConfidence 0.65, requireTrendContext true, all patterns enabled).
            strategies.add(new CandlestickPatternStrategy(
                    patternDetectionService,
                    trendContextService,
                    0.65,
                    true,
                    List.of()
            ));
        }

        return strategies;
    }

    private String getStrategyName(TradingStrategy strategy) {
        return strategy.getClass().getSimpleName()
                .replace("Strategy", "")
                .replaceAll("([A-Z])", "-$1")
                .toLowerCase()
                .substring(1);
    }

    /**
     * Walks the replayed signals in chronological order and simulates
     * flip/flatten trades exactly like the live {@code StrategyEvaluator}
     * does: the first BUY or SELL opens a position; a same-direction signal
     * while a position is open is ignored (no pyramiding); an opposite
     * signal closes it flat (it does not immediately reopen in the new
     * direction - that needs its own subsequent signal, same as live).
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

    public static class ReplayResultDTO {
        public String symbol;
        public String timeframe;
        public int candleCount;
        public int buySignals;
        public int sellSignals;
        public List<ReplaySignalDTO> signals;
        public String message;
        public PerformanceSummaryDTO performance;
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

    /**
     * "Potential profit" from replaying signals as if every BUY/SELL had
     * been taken and flattened on the next opposite signal - the same
     * open/flip/close semantics as the live {@code StrategyEvaluator}, just
     * run over historical warmup candles instead of live ticks.
     *
     * Figures are in raw price points (exit − entry for BUY, entry − exit
     * for SELL), NOT currency - {@code TradeSimulator} has no
     * quantity/lot-size input yet, matching the rest of the platform's
     * performance tracking. Multiply by an assumed position size to turn
     * this into an actual currency estimate.
     */
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