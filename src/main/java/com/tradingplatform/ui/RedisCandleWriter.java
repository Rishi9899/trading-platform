package com.tradingplatform.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingplatform.candle.CandleListener;
import com.tradingplatform.domain.candle.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Writes every 1m candle to Redis for UI consumption.
 * Strategy engine is NOT affected — this is a separate listener.
 */
@Component
public class RedisCandleWriter implements CandleListener {

    private static final Logger log = LoggerFactory.getLogger(RedisCandleWriter.class);
    private static final Duration TTL = Duration.ofDays(7);
    private static final int MAX_CANDLES_PER_KEY = 2000; // ~3 days of 1m MCX data

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisCandleWriter(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onCandleClosed(Candle candle) {
        // Only store base 1m candles — UI aggregates higher timeframes on-the-fly
        if (!"60s".equals(candle.getTimeframe()) && !"1m".equals(candle.getTimeframe())) {
            return;
        }

        try {
            String key = "candle:" + candle.getSymbol() + ":1m";
            long score = candle.getWindowStart().getEpochSecond();
            String value = objectMapper.writeValueAsString(Map.of(
                    "time", score,
                    "open", candle.getOpen(),
                    "high", candle.getHigh(),
                    "low", candle.getLow(),
                    "close", candle.getClose(),
                    "volume", candle.getVolume()
            ));

            ZSetOperations<String, String> zset = redis.opsForZSet();
            zset.add(key, value, score);

            // Trim old candles (keep last MAX_CANDLES_PER_KEY)
            long size = zset.size(key) != null ? zset.size(key) : 0;
            if (size > MAX_CANDLES_PER_KEY) {
                zset.removeRange(key, 0, size - MAX_CANDLES_PER_KEY - 1);
            }

            // Refresh TTL
            redis.expire(key, TTL);
        } catch (Exception e) {
            log.error("Failed to write candle to Redis for {}: {}", candle.getSymbol(), e.getMessage());
        }
    }

    /**
     * Write latest tick price (overwritten every tick — always fresh)
     */
    public void writeTick(String symbol, double price, long volume, long timestampMs) {
        try {
            String key = "tick:" + symbol;
            String value = objectMapper.writeValueAsString(Map.of(
                    "price", price,
                    "volume", volume,
                    "timestamp", timestampMs
            ));
            redis.opsForValue().set(key, value, Duration.ofMinutes(5));
        } catch (Exception e) {
            log.error("Failed to write tick to Redis for {}: {}", symbol, e.getMessage());
        }
    }
}