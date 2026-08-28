package com.tradingplatform.ui;

import com.tradingplatform.domain.readiness.ReadinessSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/ui/api")
public class LiveTickStreamController {

    private static final Logger log = LoggerFactory.getLogger(LiveTickStreamController.class);
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L; // 30 minutes

    private final List<TickClient> clients = new CopyOnWriteArrayList<>();

    @GetMapping(value = "/stream/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLive(@RequestParam String symbol) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        TickClient client = new TickClient(symbol.toUpperCase(), emitter);
        clients.add(client);

        Runnable detach = () -> {
            client.closed.set(true);
            clients.remove(client);
            log.debug("SSE client disconnected for symbol: {} (active: {})", symbol, clients.size());
        };

        emitter.onCompletion(detach);
        emitter.onTimeout(() -> {
            log.debug("SSE timeout for symbol: {}", symbol);
            try { emitter.complete(); } catch (Exception ignored) {}
            detach.run();
        });
        emitter.onError(e -> {
            log.debug("SSE error for symbol {}: {}", symbol, e.getClass().getSimpleName());
            detach.run();
        });

        // Send initial connection event
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("symbol", symbol)));
            log.info("SSE client connected for symbol: {} (active: {})", symbol, clients.size());
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send initial SSE event for {}: {}", symbol, e.getMessage());
            detach.run();
        }

        return emitter;
    }

    public void onTick(String symbol, BigDecimal price, long volume, long timestampMs) {
        Map<String, Object> data = Map.of(
                "symbol", symbol,
                "price", price,
                "volume", volume,
                "timestamp", timestampMs
        );

        for (TickClient client : clients) {
            if (!client.symbol.equalsIgnoreCase(symbol) || client.closed.get()) continue;

            try {
                client.emitter.send(SseEmitter.event().name("tick").data(data));
            } catch (IOException e) {
                log.debug("Failed to send tick to {}: IOException - closing connection", symbol);
                safeCloseAndRemove(client);
            } catch (IllegalStateException e) {
                log.debug("Failed to send tick to {}: IllegalStateException - closing connection", symbol);
                safeCloseAndRemove(client);
            } catch (Exception e) {
                log.warn("Unexpected error sending tick to {}: {} - closing connection", symbol, e.getMessage());
                safeCloseAndRemove(client);
            }
        }
    }

    public void onCandleClosed(String symbol, Map<String, Object> candleData) {
        for (TickClient client : clients) {
            if (!client.symbol.equalsIgnoreCase(symbol) || client.closed.get()) continue;

            try {
                client.emitter.send(SseEmitter.event().name("candle_closed").data(candleData));
            } catch (IOException e) {
                log.debug("Failed to send candle_closed to {}: IOException - closing connection", symbol);
                safeCloseAndRemove(client);
            } catch (IllegalStateException e) {
                log.debug("Failed to send candle_closed to {}: IllegalStateException - closing connection", symbol);
                safeCloseAndRemove(client);
            } catch (Exception e) {
                log.warn("Unexpected error sending candle_closed to {}: {} - closing connection", symbol, e.getMessage());
                safeCloseAndRemove(client);
            }
        }
    }

    /**
     * Called by ReadinessService when readiness updates
     */
    public void onReadinessUpdate(ReadinessSnapshot snapshot) {
        String symbol = snapshot.getSymbol();

        for (TickClient client : clients) {
            if (!client.symbol.equalsIgnoreCase(symbol) || client.closed.get()) continue;

            try {
                client.emitter.send(SseEmitter.event().name("readiness").data(snapshot));
            } catch (IOException e) {
                log.debug("Failed to send readiness to {}: IOException - closing connection", symbol);
                safeCloseAndRemove(client);
            } catch (IllegalStateException e) {
                log.debug("Failed to send readiness to {}: IllegalStateException - closing connection", symbol);
                safeCloseAndRemove(client);
            } catch (Exception e) {
                log.warn("Unexpected error sending readiness to {}: {} - closing connection", symbol, e.getMessage());
                safeCloseAndRemove(client);
            }
        }
    }

    private void safeCloseAndRemove(TickClient client) {
        if (!client.closed.compareAndSet(false, true)) return; // already closed
        try {
            client.emitter.complete();
        } catch (Exception ignored) {}
        clients.remove(client);
    }

    public int getActiveConnectionCount() {
        return clients.size();
    }

    private static final class TickClient {
        final String symbol;
        final SseEmitter emitter;
        final AtomicBoolean closed = new AtomicBoolean(false);

        TickClient(String symbol, SseEmitter emitter) {
            this.symbol = symbol;
            this.emitter = emitter;
        }
    }
}