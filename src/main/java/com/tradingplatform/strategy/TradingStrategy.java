package com.tradingplatform.strategy;

public interface TradingStrategy {

    StrategyDecision evaluate(MarketContext context);
}