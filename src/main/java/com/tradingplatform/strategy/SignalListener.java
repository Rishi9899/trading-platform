package com.tradingplatform.strategy;

import com.tradingplatform.domain.signal.Signal;

public interface SignalListener {

    void onSignal(Signal signal);
}