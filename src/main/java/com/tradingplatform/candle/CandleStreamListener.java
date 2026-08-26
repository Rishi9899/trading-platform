package com.tradingplatform.candle;

import com.tradingplatform.domain.candle.Candle;

public interface CandleStreamListener {
    void onCandle(Candle candle);
}