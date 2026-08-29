package com.tradingplatform.controller;

import com.tradingplatform.marketdata.MarketDataConnectionRegistry;
import com.tradingplatform.marketdata.fyers.FyersSidecarTickSource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health endpoint. Beyond "the app booted", this reports connection health
 * for the two external dependencies that can silently go bad without a
 * restart, with enough detail to tell the failure modes apart:
 *
 *   - DOWN:      Java cannot reach the FYERS sidecar at all (process not
 *                running, wrong port, network issue). Nothing will flow
 *                until the sidecar process itself is back up. This is the
 *                only thing that can push overall status to DOWN - it's
 *                the one dependency the whole pipeline needs.
 *   - DEGRADED:  Either (a) the sidecar is reachable but not streaming
 *                FYERS data - most commonly the daily FYERS auth token
 *                expired, needing re-authentication on the sidecar side,
 *                not a Java restart - or (b) Redis is unreachable, which
 *                only affects the UI's live candle/tick cache; the
 *                strategy engine and Postgres persistence are unaffected
 *                (see RedisCandleWriter), so Redis alone never reaches DOWN.
 *   - UP:        Fully healthy (or intentionally running on fake data).
 */
@RestController
public class HealthController {

    private final MarketDataConnectionRegistry connectionRegistry;
    private final StringRedisTemplate redisTemplate;

    public HealthController(MarketDataConnectionRegistry connectionRegistry,
                            StringRedisTemplate redisTemplate) {
        this.connectionRegistry = connectionRegistry;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> marketData = buildMarketDataStatus();
        Map<String, Object> redis = buildRedisStatus();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", worseOf((String) marketData.get("status"), (String) redis.get("status")));
        response.put("timestamp", Instant.now().toString());
        response.put("marketData", marketData);
        response.put("redis", redis);
        return response;
    }

    private Map<String, Object> buildMarketDataStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        String sourceType = connectionRegistry.getActiveSourceType();
        status.put("tickSource", sourceType);

        switch (sourceType) {
            case "fyers-sidecar" -> {
                var fyersSourceOpt = connectionRegistry.getFyersTickSource();
                if (fyersSourceOpt.isEmpty()) {
                    status.put("status", "DOWN");
                    status.put("detail", "fyers-sidecar selected but not initialized yet");
                    return status;
                }

                FyersSidecarTickSource fyersSource = fyersSourceOpt.get();
                FyersSidecarTickSource.ConnectionStatus conn = fyersSource.getConnectionStatus();

                status.put("sidecarUrl", fyersSource.getSidecarUrl());
                status.put("sidecarConnected", conn.sidecarConnected());
                status.put("fyersUpstreamStatus", conn.fyersUpstreamStatus().name());
                status.put("reconnectAttempts", conn.reconnectAttempts());
                status.put("millisSinceLastMessage", conn.millisSinceLastMessage());
                if (conn.fyersLastErrorMessage() != null) {
                    status.put("fyersLastError", conn.fyersLastErrorMessage());
                }

                if (!conn.sidecarConnected()) {
                    status.put("status", "DOWN");
                    status.put("detail", "Cannot reach the FYERS sidecar at " + fyersSource.getSidecarUrl()
                            + " - check that the sidecar process is running and the port is open. "
                            + "Java will keep retrying on its own; no restart needed once the sidecar is back.");
                } else {
                    switch (conn.fyersUpstreamStatus()) {
                        case AUTH_EXPIRED -> {
                            status.put("status", "DEGRADED");
                            status.put("detail", "Sidecar is reachable, but its FYERS auth token has expired. "
                                    + "Re-authenticate the sidecar (refresh its FYERS access token); "
                                    + "no Java restart is needed.");
                        }
                        case DISCONNECTED -> {
                            status.put("status", "DEGRADED");
                            status.put("detail", "Sidecar is reachable but not currently connected to FYERS "
                                    + "(and it isn't reporting a token/auth issue - likely a transient network "
                                    + "blip or FYERS-side outage). The sidecar retries this on its own.");
                        }
                        case UNKNOWN -> {
                            status.put("status", "DEGRADED");
                            status.put("detail", "Sidecar just connected - waiting for its first heartbeat "
                                    + "to confirm FYERS status.");
                        }
                        case CONNECTED -> {
                            status.put("status", "UP");
                            status.put("detail", "Streaming live data from FYERS.");
                        }
                    }
                }
            }
            case "fake" -> {
                status.put("status", "UP");
                status.put("detail", "Using the synthetic tick generator (app.tick.source=fake) - no live "
                        + "market data connection in play.");
            }
            default -> {
                status.put("status", "DOWN");
                status.put("detail", "Market data pipeline has not started yet.");
            }
        }

        return status;
    }

    private Map<String, Object> buildRedisStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) RedisConnection::ping);
            if (pong != null && pong.equalsIgnoreCase("PONG")) {
                status.put("status", "UP");
                status.put("detail", "Redis reachable.");
            } else {
                // Reached Redis but got a response we don't recognize - treat
                // as degraded rather than assuming healthy.
                status.put("status", "DEGRADED");
                status.put("detail", "Unexpected PING response from Redis: " + pong);
            }
        } catch (Exception e) {
            // This is the "total connection missing / port not reachable"
            // case for Redis - connection refused, DNS failure, or (with the
            // spring.data.redis.timeout set in application.yml) a timeout
            // instead of hanging. It's a real DOWN for Redis itself, but
            // capped at DEGRADED overall - see class javadoc for why.
            status.put("status", "DOWN");
            status.put("detail", "Cannot reach Redis: " + e.getMessage() + ". Only the UI's live candle/tick "
                    + "cache is affected - the strategy engine and Postgres persistence keep running. The "
                    + "underlying client (Lettuce) retries its own connection automatically; no restart is "
                    + "needed once Redis is back up.");
        }
        return status;
    }

    /** Redis is capped at DEGRADED here - see class javadoc. */
    private static String worseOf(String marketDataStatus, String redisStatus) {
        if ("DOWN".equals(marketDataStatus)) return "DOWN";
        if ("DEGRADED".equals(marketDataStatus)) return "DEGRADED";
        if (!"UP".equals(redisStatus)) return "DEGRADED";
        return "UP";
    }
}