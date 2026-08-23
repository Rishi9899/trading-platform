package com.tradingplatform.strategy;

import com.tradingplatform.domain.signal.Signal;

public class LoggingSignalListener implements SignalListener {

    @Override
    public void onSignal(Signal signal) {
        System.out.println("[SIGNAL][" + signal.getTimestamp() + "] " + signal.getSymbol()
                + " " + signal.getSignalType()
                + " @ " + signal.getPrice()
                + " (" + signal.getReason() + ")"
                + " strategyInstance=" + signal.getStrategyInstance().getId());
    }
}