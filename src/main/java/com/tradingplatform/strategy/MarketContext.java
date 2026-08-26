package com.tradingplatform.strategy;

import com.tradingplatform.domain.candle.Candle;

import java.util.List;

/**
 * currentCandle/recentCandles are always the strategy instance's own bound
 * timeframe. higherTimeframeCandles is an optional read-only snapshot of a
 * *different*, coarser timeframe (e.g. a 5m strategy checking 15m trend) -
 * it does NOT trigger evaluation on its own closes, it's just the latest
 * known history at the moment this candle closed. Empty/absent whenever no
 * confirmation timeframe is configured, or that timeframe hasn't produced
 * any candles yet.
 */
public record MarketContext(String symbol, Candle currentCandle, List<Candle> recentCandles,
                            String higherTimeframe, List<Candle> higherTimeframeCandles) {

    public MarketContext(String symbol, Candle currentCandle, List<Candle> recentCandles) {
        this(symbol, currentCandle, recentCandles, null, List.of());
    }

    public boolean hasHigherTimeframeData() {
        return higherTimeframeCandles != null && !higherTimeframeCandles.isEmpty();
    }
}
