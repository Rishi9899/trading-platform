package com.tradingplatform.strategy.impl;

import com.tradingplatform.domain.signal.SignalType;
import com.tradingplatform.indicator.AverageTrueRange;
import com.tradingplatform.indicator.BollingerBands;
import com.tradingplatform.indicator.BollingerBands.BandValue;
import com.tradingplatform.strategy.MarketContext;
import com.tradingplatform.strategy.StrategyDecision;
import com.tradingplatform.strategy.TradingStrategy;

import java.math.BigDecimal;

/**
 * Buys when price closes above the upper Bollinger band, sells when it
 * closes below the lower band - a breakout (not mean-reversion) read on
 * the bands. Gated by ATR: a breakout during a low-volatility regime
 * (band width < minBandWidthAtrMultiple * ATR) is treated as noise and
 * downgraded to HOLD, since tight bands make breakouts far more likely
 * to be false signals that snap back.
 */
public class BollingerBreakoutStrategy implements TradingStrategy {

    private final BollingerBands bollingerBands;
    private final AverageTrueRange atr;
    private final BigDecimal minBandWidthAtrMultiple;

    public BollingerBreakoutStrategy(int bandPeriod, BigDecimal stdDevMultiplier,
                                     int atrPeriod, BigDecimal minBandWidthAtrMultiple) {
        this.bollingerBands = new BollingerBands(bandPeriod, stdDevMultiplier);
        this.atr = new AverageTrueRange(atrPeriod);
        this.minBandWidthAtrMultiple = minBandWidthAtrMultiple;
    }

    @Override
    public StrategyDecision evaluate(MarketContext context) {
        var candle = context.currentCandle();
        bollingerBands.update(candle);
        atr.update(candle);

        if (!bollingerBands.isReady() || !atr.isReady()) {
            return null; // Warming up
        }

        BandValue bands = bollingerBands.value().orElseThrow();
        BigDecimal atrValue = atr.value().orElseThrow();
        BigDecimal bandWidth = bands.upper().subtract(bands.lower());
        boolean sufficientlyVolatile = bandWidth.compareTo(atrValue.multiply(minBandWidthAtrMultiple)) >= 0;

        BigDecimal close = candle.getClose();
        boolean brokeAbove = close.compareTo(bands.upper()) > 0;
        boolean brokeBelow = close.compareTo(bands.lower()) < 0;

        if (brokeAbove && sufficientlyVolatile) {
            return new StrategyDecision(SignalType.BUY, close, confidence(close, bands.upper(), bandWidth),
                    "close broke above upper Bollinger band with sufficient band width vs ATR");
        }
        if (brokeBelow && sufficientlyVolatile) {
            return new StrategyDecision(SignalType.SELL, close, confidence(close, bands.lower(), bandWidth),
                    "close broke below lower Bollinger band with sufficient band width vs ATR");
        }
        if (brokeAbove || brokeBelow) {
            return new StrategyDecision(SignalType.HOLD, close, 0.0,
                    "band breakout but band width too narrow relative to ATR - likely noise");
        }
        return new StrategyDecision(SignalType.HOLD, close, 0.0, "close within Bollinger bands");
    }

    private double confidence(BigDecimal close, BigDecimal band, BigDecimal bandWidth) {
        double baseConfidence = 0.55;
        double breachFraction = close.subtract(band).abs().divide(bandWidth, java.math.MathContext.DECIMAL64)
                .doubleValue();
        double bonus = Math.min(Math.max(breachFraction, 0.0), 0.44);
        return Math.min(baseConfidence + bonus, 0.99);
    }
}
