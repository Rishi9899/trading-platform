package com.tradingplatform.marketdata;

/**
 * Anything that produces a stream of ticks implements this: today it's
 * FakeTickGenerator, later it will be the FYERS WebSocket client, and
 * eventually a historical-data adapter for backtesting.
 *
 * This is the seam that lets everything downstream (candle engine,
 * strategy engine) stay completely ignorant of where ticks come from.
 */
public interface TickSource {

    /**
     * Start producing ticks. Implementations push ticks to registered
     * listeners; this method should not block.
     */
    void start();

    /**
     * Stop producing ticks and release any resources (threads, connections).
     */
    void stop();

    void addListener(TickListener listener);
}
