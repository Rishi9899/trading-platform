package com.tradingplatform.marketdata;

import com.tradingplatform.domain.tick.Tick;

/**
 * Anything that wants to consume ticks implements this.
 * CandleBuilder is the first (and for now only) implementation.
 */
public interface TickListener {

    void onTick(Tick tick);
}
