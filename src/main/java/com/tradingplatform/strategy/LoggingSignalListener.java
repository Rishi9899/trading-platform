package com.tradingplatform.strategy;

import com.tradingplatform.domain.signal.Signal;
import com.tradingplatform.domain.signal.SignalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingSignalListener implements SignalListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingSignalListener.class);

    @Override
    public void onSignal(Signal signal) {
        // Suppress generic HOLD signals from cluttering logs
        if (signal.getSignalType() == SignalType.HOLD) {
            return;
        }

        String confidenceFormatted = (signal.getConfidence() != null)
                ? String.format("%.1f%%", signal.getConfidence() * 100)
                : "N/A";

        log.info("[SIGNAL GENERATED] Instance #{} | {} [{}] | {} | Price: {} | Confidence: {} | Reason: {}",
                signal.getStrategyInstance().getId(),
                signal.getSymbol(),
                signal.getTimeframe(),
                signal.getSignalType(),
                signal.getPrice(),
                confidenceFormatted,
                signal.getReason()
        );
    }
}