package com.tradingplatform.strategy.impl;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.strategy.MarketContext;
import com.tradingplatform.strategy.StrategyDecision;
import com.tradingplatform.strategy.TradingStrategy;

public class CandleDirectionStrategy implements TradingStrategy {

    @Override
    public StrategyDecision evaluate(MarketContext context) {
        Candle candle = context.currentCandle();
        int comparison = candle.getClose().compareTo(candle.getOpen());

        if (comparison > 0) {
            return new StrategyDecision(SignalType.BUY, candle.getClose(), null, "close above open");
        }
        if (comparison < 0) {
            return new StrategyDecision(SignalType.SELL, candle.getClose(), null, "close below open");
        }
        return new StrategyDecision(SignalType.HOLD, candle.getClose(), null, "close equals open");
    }
}