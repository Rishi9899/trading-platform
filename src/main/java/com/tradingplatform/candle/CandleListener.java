package com.tradingplatform.candle;

import com.tradingplatform.domain.candle.Candle;

/**
 * Anything that wants to be notified when a candle window closes
 * implements this. In Phase 5+ this will be the Strategy Engine;
 * for now it's just LoggingCandleListener so we can see it working.
 */
public interface CandleListener {

    void onCandleClosed(Candle candle);
}
