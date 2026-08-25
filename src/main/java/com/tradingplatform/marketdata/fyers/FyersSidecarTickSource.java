package com.tradingplatform.marketdata.fyers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
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

public class FyersSidecarTickSource extends TextWebSocketHandler implements TickSource {

    private static final Logger log = LoggerFactory.getLogger(FyersSidecarTickSource.class);

    private final String sidecarUrl;
    private final long reconnectDelayMillis;
    private final List<TickListener> listeners = new CopyOnWriteArrayList<>();
    private final List<HistoricalDataListener> historyListeners = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledExecutorService reconnectExecutor;
    private StandardWebSocketClient client;

    public interface HistoricalDataListener {
        void onHistoricalCandle(String symbol, Instant timestamp, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume);
        void onHistoryComplete(String symbol);
    }

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

    public void addHistoryListener(HistoricalDataListener listener) {
        historyListeners.add(listener);
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
            JsonNode root = objectMapper.readTree(message.getPayload());
            String type = root.has("type") ? root.get("type").asText() : "tick";

            if ("history".equals(type)) {
                handleHistoryMessage(root);
            } else if ("tick".equals(type)) {
                handleTickMessage(root);
            } else {
                log.warn("Unknown message type received: {}", type);
            }
        } catch (Exception e) {
            log.error("Failed to parse payload from sidecar: {} (raw={})", e.getMessage(), message.getPayload());
        }
    }

    private void handleHistoryMessage(JsonNode root) {
        String symbol = root.get("symbol").asText();
        JsonNode candlesNode = root.get("candles");

        if (candlesNode != null && candlesNode.isArray()) {
            log.info("Received batch of {} historical candles for {}", candlesNode.size(), symbol);

            for (JsonNode node : candlesNode) {
                Instant ts = Instant.ofEpochMilli(node.get("timestamp").asLong());
                BigDecimal open = BigDecimal.valueOf(node.get("open").asDouble());
                BigDecimal high = BigDecimal.valueOf(node.get("high").asDouble());
                BigDecimal low = BigDecimal.valueOf(node.get("low").asDouble());
                BigDecimal close = BigDecimal.valueOf(node.get("close").asDouble());
                long volume = node.get("volume").asLong();

                for (HistoricalDataListener listener : historyListeners) {
                    listener.onHistoricalCandle(symbol, ts, open, high, low, close, volume);
                }
            }

            // Notify completeness after inserting the batch
            for (HistoricalDataListener listener : historyListeners) {
                listener.onHistoryComplete(symbol);
            }
        }
    }

    private void handleTickMessage(JsonNode root) throws Exception {
        IncomingTick incoming = objectMapper.treeToValue(root, IncomingTick.class);
        Tick tick = toTick(incoming);
        for (TickListener listener : listeners) {
            listener.onTick(tick);
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