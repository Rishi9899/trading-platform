package com.tradingplatform.domain.candle;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface MarketCandleRepository extends JpaRepository<MarketCandle, Long> {

    Optional<MarketCandle> findBySymbolAndTimeframeAndWindowStart(String symbol, String timeframe, Instant windowStart);
}