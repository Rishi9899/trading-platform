package com.tradingplatform.strategy;

import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.domain.signal.SignalRepository;

public class PersistingSignalListener implements SignalListener {

    private final SignalRepository signalRepository;

    public PersistingSignalListener(SignalRepository signalRepository) {
        this.signalRepository = signalRepository;
    }

    @Override
    public void onSignal(Signal signal) {
        signalRepository.save(signal);
    }
}