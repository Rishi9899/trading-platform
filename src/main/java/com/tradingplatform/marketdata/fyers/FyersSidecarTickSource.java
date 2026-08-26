package com.tradingplatform.marketdata.fyers;

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
import java.util.ArrayList;
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
    private final List<TickListener> tickListeners = new CopyOnWriteArrayList<>();
    private final List<HistoricalDataListener> historyListeners = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledExecutorService reconnectExecutor;
    private StandardWebSocketClient client;

    public record HistoricalCandleData(
            String symbol,
            Instant timestamp,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            long volume
    ) {}

    public interface HistoricalDataListener {
        void onHistoricalBatchReceived(String symbol, List<HistoricalCandleData> candleBatch);
        void onHistoryComplete(String symbol);
    }

    public FyersSidecarTickSource(String sidecarUrl, long reconnectDelayMillis) {
        this.sidecarUrl = sidecarUrl;
        this.reconnectDelayMillis = reconnectDelayMillis;
    }

    @Override
    public void addListener(TickListener listener) {
        tickListeners.add(listener);
    }

    public void addHistoryListener(HistoricalDataListener listener) {
        historyListeners.add(listener);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());

            if (root.has("type")) {
                String type = root.get("type").asText();

                switch (type) {
                    case "history" -> handleHistoryMessage(root);
                    case "history_complete" -> handleHistoryCompleteMessage(root);
                    case "tick" -> handleTickMessage(root);
                    case "heartbeat" -> {
                        boolean fyersConnected = root.path("fyersConnected").asBoolean(false);
                        log.debug("Sidecar heartbeat received. FYERS WS Connected: {}", fyersConnected);
                    }
                    default -> log.warn("Unknown message type received: {}", type);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse payload from sidecar: {}", e.getMessage());
        }
    }

    private void handleHistoryMessage(JsonNode root) {
        String symbol = root.get("symbol").asText();
        JsonNode candlesNode = root.get("candles");

        if (candlesNode != null && candlesNode.isArray()) {
            List<HistoricalCandleData> batch = new ArrayList<>(candlesNode.size());

            for (JsonNode node : candlesNode) {
                Instant ts = Instant.ofEpochMilli(node.get("timestamp").asLong());
                BigDecimal open = BigDecimal.valueOf(node.get("open").asDouble());
                BigDecimal high = BigDecimal.valueOf(node.get("high").asDouble());
                BigDecimal low = BigDecimal.valueOf(node.get("low").asDouble());
                BigDecimal close = BigDecimal.valueOf(node.get("close").asDouble());
                long volume = node.get("volume").asLong();

                batch.add(new HistoricalCandleData(symbol, ts, open, high, low, close, volume));
            }

            // Flush complete batch snapshot to all history listeners
            for (HistoricalDataListener listener : historyListeners) {
                listener.onHistoricalBatchReceived(symbol, batch);
            }
            log.info("Received and dispatched snapshot batch of {} historical candles for {}", batch.size(), symbol);
        }
    }

    private void handleHistoryCompleteMessage(JsonNode root) {
        String symbol = root.get("symbol").asText();
        for (HistoricalDataListener listener : historyListeners) {
            listener.onHistoryComplete(symbol);
        }
    }

    private void handleTickMessage(JsonNode root) {
        String symbol = root.get("symbol").asText();
        double price = root.get("price").asDouble();
        long volume = root.get("volume").asLong();
        long timestampMillis = root.get("timestamp").asLong();

        Instant timestamp = timestampMillis > 0 ? Instant.ofEpochMilli(timestampMillis) : Instant.now();
        Tick tick = new Tick(symbol, timestamp, BigDecimal.valueOf(price), volume);

        for (TickListener listener : tickListeners) {
            listener.onTick(tick);
        }
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

    private void connect() {
        if (!running.get()) return;
        try {
            log.info("Connecting to sidecar at {}", sidecarUrl);
            client.execute(this, sidecarUrl);
        } catch (Exception e) {
            log.error("Failed to connect to sidecar: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) return;
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
}