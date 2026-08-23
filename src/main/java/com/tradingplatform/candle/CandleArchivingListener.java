package com.tradingplatform.candle;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.domain.candle.MarketCandle;
import com.tradingplatform.domain.candle.MarketCandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CandleArchivingListener implements CandleListener {

    private static final Logger log = LoggerFactory.getLogger(CandleArchivingListener.class);

    private final MarketCandleRepository repository;

    public CandleArchivingListener(MarketCandleRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onCandleClosed(Candle candle) {
        try {
            boolean alreadyStored = repository
                    .findBySymbolAndTimeframeAndWindowStart(candle.getSymbol(), candle.getTimeframe(), candle.getWindowStart())
                    .isPresent();
            if (alreadyStored) {
                return;
            }
            repository.save(new MarketCandle(
                    candle.getSymbol(), candle.getTimeframe(), candle.getWindowStart(), candle.getWindowEnd(),
                    candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(), candle.getVolume()
            ));
        } catch (Exception e) {
            log.error("Failed to archive candle for {} at {}: {}",
                    candle.getSymbol(), candle.getWindowStart(), e.getMessage(), e);
        }
    }
}