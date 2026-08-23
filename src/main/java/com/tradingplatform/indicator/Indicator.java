package com.tradingplatform.indicator;

import com.tradingplatform.domain.candle.Candle;

import java.util.Optional;

/**
 * A stateful, incrementally-updated indicator. update() is called once
 * per closed candle - O(1) work per call, never a recompute over full
 * history. This matters once hundreds of strategy instances each carry
 * their own indicators: O(1) per update is the difference between a
 * backtest finishing in seconds vs. minutes at scale.
 *
 * value() returns empty until enough candles have been seen to produce
 * a meaningful reading (see isReady()) - callers must check readiness
 * before trusting the value, not assume it's always present.
 */
public interface Indicator<T> {

    void update(Candle candle);

    boolean isReady();

    Optional<T> value();
}