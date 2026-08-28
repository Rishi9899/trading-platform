package com.tradingplatform.controller;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.signal.SignalType;
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

    public SignalReplayController(StrategyEngine strategyEngine) {
        this.strategyEngine = strategyEngine;
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

        // Pattern strategy doesn't exist yet, return empty result
        ReplayResultDTO result = new ReplayResultDTO();
        result.symbol = symbol;
        result.timeframe = timeframe;
        result.signals = new ArrayList<>();
        result.message = "Pattern strategy not implemented yet";
        return result;
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
        // Skip candlestick-pattern as it doesn't exist yet

        return strategies;
    }

    private String getStrategyName(TradingStrategy strategy) {
        return strategy.getClass().getSimpleName()
                .replace("Strategy", "")
                .replaceAll("([A-Z])", "-$1")
                .toLowerCase()
                .substring(1);
    }

    public static class ReplayResultDTO {
        public String symbol;
        public String timeframe;
        public int candleCount;
        public int buySignals;
        public int sellSignals;
        public List<ReplaySignalDTO> signals;
        public String message;
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
}