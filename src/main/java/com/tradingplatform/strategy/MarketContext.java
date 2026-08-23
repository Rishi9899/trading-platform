package com.tradingplatform.strategy;

import com.tradingplatform.domain.candle.Candle;

import java.util.List;

public record MarketContext(String symbol, Candle currentCandle, List<Candle> recentCandles) {
}