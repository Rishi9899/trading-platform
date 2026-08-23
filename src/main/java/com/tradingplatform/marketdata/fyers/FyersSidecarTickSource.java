package com.tradingplatform.marketdata.fyers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingplatform.domain.tick.Tick;
import com.tradingplatform.marketdata.TickListener;
import com.tradingplatform.marketdata.TickSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TickSource backed by a local Python sidecar (sidecar/sidecar.py) instead
 * of talking to FYERS directly from Java.
 *
 * Why: FYERS' v3 data socket sends a proprietary binary format that is not
 * publicly documented - only the official Python/Node/Go SDKs decode it
 * correctly. Rather than reverse-engineer that format in Java (real risk
 * of silently wrong prices), the sidecar runs the official Python SDK,
 * lets it do the decoding, and republishes plain JSON over a local
 * WebSocket that this class connects to as a client.
 *
 * Everything downstream (CandleBuilder, later the Strategy Engine) is
 * unaffected by this choice - that's the point of the TickSource
 * interface from Phase 1.
 */
public class FyersSidecarTickSource extends TextWebSocketHandler implements TickSource {

    private static final Logger log = LoggerFactory.getLogger(FyersSidecarTickSource.class);

    private final String sidecarUrl;
    private final long reconnectDelayMillis;
    private final List<TickListener> listeners = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledExecutorService reconnectExecutor;
    private StandardWebSocketClient client;

    public FyersSidecarTickSource(String sidecarUrl, long reconnectDelayMillis) {
        this.sidecarUrl = sidecarUrl;
        this.reconnectDelayMillis = reconnectDelayMillis;
    }

    @Override
    public void start() {
        running.set(true);
        client = new StandardWebSocketClient();
        reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fyers-sidecar-reconnect");
            t.setDaemon(true);
            return t;
        });
        connect();
    }

    @Override
    public void stop() {
        running.set(false);
        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
        }
    }

    @Override
    public void addListener(TickListener listener) {
        listeners.add(listener);
    }

    private void connect() {
        if (!running.get()) {
            return;
        }
        try {
            log.info("Connecting to sidecar at {}", sidecarUrl);
            client.execute(this, sidecarUrl);
        } catch (Exception e) {
            log.error("Failed to connect to sidecar: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        log.info("Reconnecting to sidecar in {}ms", reconnectDelayMillis);
        reconnectExecutor.schedule(this::connect, reconnectDelayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Connected to sidecar");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("Sidecar connection closed: {}", status);
        scheduleReconnect();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Sidecar transport error: {}", exception.getMessage());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            IncomingTick incoming = objectMapper.readValue(message.getPayload(), IncomingTick.class);
            Tick tick = toTick(incoming);
            for (TickListener listener : listeners) {
                listener.onTick(tick);
            }
        } catch (Exception e) {
            log.error("Failed to parse tick from sidecar: {} (raw={})", e.getMessage(), message.getPayload());
        }
    }

    private Tick toTick(IncomingTick incoming) {
        Instant timestamp = incoming.timestamp() > 0
                ? Instant.ofEpochMilli(incoming.timestamp())
                : Instant.now();
        return new Tick(incoming.symbol(), timestamp, BigDecimal.valueOf(incoming.price()), incoming.volume());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IncomingTick(String symbol, double price, long volume, long timestamp) {
    }
}