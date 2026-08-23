package com.tradingplatform.candle;

import com.tradingplatform.domain.candle.Candle;

public class LoggingCandleListener implements CandleListener {

    @Override
    public void onCandleClosed(Candle candle) {
        System.out.println("[CANDLE CLOSED][" + candle.getTimeframe() + "] " + candle);
    }
}