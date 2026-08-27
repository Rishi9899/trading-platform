package com.tradingplatform.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * UI-only candle endpoint. Reads 1m candles from Redis, aggregates
 * to whatever timeframe the frontend requests.
 */
@RestController
@RequestMapping("/ui/api")
public class UiCandleController {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public UiCandleController(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * GET /ui/api/candles?symbol=MCX:CRUDEOIL26SEPFUT&timeframe=5m&limit=200
     *
     * Reads 1m candles from Redis, aggregates to requested timeframe.
     * Supported: 1m, 3m, 5m, 15m, 30m, 1h
     */
    @GetMapping("/candles")
    public List<Map<String, Object>> getCandles(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "5m") String timeframe,
            @RequestParam(defaultValue = "200") int limit) {

        int aggregationFactor = parseTimeframeMinutes(timeframe);
        int rawCandlesNeeded = limit * aggregationFactor;

        String key = "candle:" + symbol + ":1m";
        ZSetOperations<String, String> zset = redis.opsForZSet();

        Long totalSize = zset.size(key);
        if (totalSize == null || totalSize == 0) {
            return List.of();
        }

        // Get last N raw candles
        long start = Math.max(totalSize - rawCandlesNeeded, 0);
        Set<String> rawEntries = zset.range(key, start, -1);
        if (rawEntries == null || rawEntries.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> rawCandles = new ArrayList<>();
        for (String entry : rawEntries) {
            try {
                rawCandles.add(objectMapper.readValue(entry, new TypeReference<>() {}));
            } catch (Exception ignored) {}
        }

        if (aggregationFactor == 1) {
            return rawCandles.subList(Math.max(rawCandles.size() - limit, 0), rawCandles.size());
        }

        // Aggregate 1m → requested timeframe
        List<Map<String, Object>> aggregated = aggregate(rawCandles, aggregationFactor);
        return aggregated.subList(Math.max(aggregated.size() - limit, 0), aggregated.size());
    }

    /**
     * GET /ui/api/tick?symbol=MCX:CRUDEOIL26SEPFUT
     * Returns latest tick price.
     */
    @GetMapping("/tick")
    public Map<String, Object> getLatestTick(@RequestParam String symbol) {
        String key = "tick:" + symbol;
        String value = redis.opsForValue().get(key);
        if (value == null) return Map.of("symbol", symbol, "price", 0, "status", "no_data");

        try {
            Map<String, Object> tick = objectMapper.readValue(value, new TypeReference<>() {});
            tick.put("symbol", symbol);
            return tick;
        } catch (Exception e) {
            return Map.of("symbol", symbol, "status", "error");
        }
    }

    private List<Map<String, Object>> aggregate(List<Map<String, Object>> candles, int factor) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (int i = 0; i < candles.size(); i += factor) {
            int end = Math.min(i + factor, candles.size());
            if (end - i < factor && i > 0) break; // Skip incomplete last group

            List<Map<String, Object>> group = candles.subList(i, end);

            BigDecimal open = toBigDecimal(group.get(0).get("open"));
            BigDecimal close = toBigDecimal(group.get(group.size() - 1).get("close"));
            BigDecimal high = group.stream()
                    .map(c -> toBigDecimal(c.get("high")))
                    .max(BigDecimal::compareTo).orElse(open);
            BigDecimal low = group.stream()
                    .map(c -> toBigDecimal(c.get("low")))
                    .min(BigDecimal::compareTo).orElse(open);
            long volume = group.stream()
                    .mapToLong(c -> ((Number) c.get("volume")).longValue())
                    .sum();
            long time = ((Number) group.get(0).get("time")).longValue();

            result.add(Map.of(
                    "time", time,
                    "open", open,
                    "high", high,
                    "low", low,
                    "close", close,
                    "volume", volume
            ));
        }
        return result;
    }

    private int parseTimeframeMinutes(String timeframe) {
        return switch (timeframe.toLowerCase()) {
            case "1m" -> 1;
            case "3m" -> 3;
            case "5m" -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "1h" -> 60;
            default -> 5;
        };
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(value.toString());
    }
}