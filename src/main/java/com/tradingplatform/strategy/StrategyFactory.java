package com.tradingplatform.strategy;

import java.util.Map;

public interface StrategyFactory {

    TradingStrategy create(Map<String, Object> parameters);
}