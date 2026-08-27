package com.tradingplatform.ui;

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

    private final List<TickClient> clients = new CopyOnWriteArrayList<>();

    @GetMapping(value = "/stream/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLive(@RequestParam String symbol) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        TickClient client = new TickClient(symbol.toUpperCase(), emitter);
        clients.add(client);

        Runnable detach = () -> {
            client.closed.set(true);
            clients.remove(client);
        };

        emitter.onCompletion(detach);
        emitter.onTimeout(() -> {
            try { emitter.complete(); } catch (Exception ignored) {}
            detach.run();
        });
        emitter.onError(e -> detach.run());

        // optional hello event to validate connection
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("symbol", symbol)));
        } catch (IOException | IllegalStateException e) {
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
            } catch (IOException | IllegalStateException e) {
                safeCloseAndRemove(client);
            }
        }
    }

    public void onCandleClosed(String symbol, Map<String, Object> candleData) {
        for (TickClient client : clients) {
            if (!client.symbol.equalsIgnoreCase(symbol) || client.closed.get()) continue;

            try {
                client.emitter.send(SseEmitter.event().name("candle_closed").data(candleData));
            } catch (IOException | IllegalStateException e) {
                safeCloseAndRemove(client);
            }
        }
    }

    private void safeCloseAndRemove(TickClient client) {
        if (!client.closed.compareAndSet(false, true)) return; // already closed
        try { client.emitter.complete(); } catch (Exception ignored) {}
        clients.remove(client);
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