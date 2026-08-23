package com.tradingplatform.strategy;

import com.tradingplatform.domain.signal.SignalType;

import java.math.BigDecimal;

public record StrategyDecision(SignalType signalType, BigDecimal price, Double confidence, String reason) {
}