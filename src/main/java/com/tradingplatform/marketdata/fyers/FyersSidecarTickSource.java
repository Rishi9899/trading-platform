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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick source that consumes market data from the Python FYERS sidecar over a
 * plain WebSocket.
 *
 * Connection lifecycle is intentionally explicit, because there are two
 * different failure modes that must be handled differently:
 *
 *   1. Sidecar/network blip (Python restarts, laptop sleeps, Wi-Fi drops).
 *      Java should reconnect on its own, with backoff, and resume streaming
 *      once the sidecar is back - no human involved.
 *
 *   2. FYERS upstream auth expiry (daily token expiry on the FYERS side).
 *      Reconnecting Java<->Python does NOT fix this - the sidecar itself
 *      needs a fresh token. Java should keep running and keep its socket to
 *      the sidecar open/retried, but surface "FYERS is not connected" as a
 *      status flag rather than pretending everything is fine.
 *
 * This class never needs to be restarted for either case; only the FYERS
 * daily login (handled outside this process, by re-authenticating the
 * sidecar) resolves case 2.
 */
public class FyersSidecarTickSource extends TextWebSocketHandler implements TickSource {

    private static final Logger log = LoggerFactory.getLogger(FyersSidecarTickSource.class);

    /** How often the watchdog checks for a stale/zombie connection. */
    private static final long WATCHDOG_INTERVAL_MILLIS = 5_000;

    private final String sidecarUrl;
    private final long baseReconnectDelayMillis;
    private final long maxReconnectDelayMillis;
    private final long staleConnectionTimeoutMillis;

    private final List<TickListener> tickListeners = new CopyOnWriteArrayList<>();
    private final List<HistoricalDataListener> historyListeners = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connectInFlight = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicLong lastMessageAtMillis = new AtomicLong(0L);
    private final AtomicBoolean fyersUpstreamConnected = new AtomicBoolean(false);

    private volatile WebSocketSession currentSession;
    private volatile ScheduledFuture<?> watchdogTask;
    private volatile FyersUpstreamStatus fyersUpstreamStatus = FyersUpstreamStatus.UNKNOWN;
    private volatile String fyersLastErrorMessage;

    private ScheduledExecutorService executor;
    private StandardWebSocketClient client;

    /**
     * Status of the sidecar's connection to FYERS itself, as reported in its
     * heartbeat. This is independent of whether Java can reach the sidecar -
     * see {@link ConnectionStatus#sidecarConnected()} for that.
     */
    public enum FyersUpstreamStatus {
        /** No heartbeat received yet (or sidecar unreachable) - nothing known. */
        UNKNOWN,
        /** Sidecar is streaming live data from FYERS. */
        CONNECTED,
        /** Sidecar's FYERS token appears to have expired - needs re-authentication, not a Java restart. */
        AUTH_EXPIRED,
        /** Sidecar is up but not connected to FYERS for some other reason (transient network blip, FYERS outage, etc.). */
        DISCONNECTED
    }

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

    /** Point-in-time snapshot of connection health, safe to poll from a health endpoint. */
    public record ConnectionStatus(
            boolean sidecarConnected,
            boolean fyersUpstreamConnected,
            FyersUpstreamStatus fyersUpstreamStatus,
            String fyersLastErrorMessage,
            int reconnectAttempts,
            long millisSinceLastMessage
    ) {}

    public FyersSidecarTickSource(String sidecarUrl, long reconnectDelayMillis) {
        // Defaults derived from the base delay so existing config (a single
        // reconnect-delay-millis value) keeps working without any YAML changes.
        this(sidecarUrl,
                reconnectDelayMillis,
                Math.max(reconnectDelayMillis * 12, 60_000),
                Math.max(reconnectDelayMillis * 4, 20_000));
    }

    public FyersSidecarTickSource(String sidecarUrl,
                                  long baseReconnectDelayMillis,
                                  long maxReconnectDelayMillis,
                                  long staleConnectionTimeoutMillis) {
        this.sidecarUrl = sidecarUrl;
        this.baseReconnectDelayMillis = baseReconnectDelayMillis;
        this.maxReconnectDelayMillis = maxReconnectDelayMillis;
        this.staleConnectionTimeoutMillis = staleConnectionTimeoutMillis;
    }

    @Override
    public void addListener(TickListener listener) {
        tickListeners.add(listener);
    }

    public void addHistoryListener(HistoricalDataListener listener) {
        historyListeners.add(listener);
    }

    public String getSidecarUrl() {
        return sidecarUrl;
    }

    /** Current connection health - safe to expose via /api/health or similar. */
    public ConnectionStatus getConnectionStatus() {
        WebSocketSession session = currentSession;
        boolean sidecarConnected = session != null && session.isOpen();
        long lastMsg = lastMessageAtMillis.get();
        long sinceLast = lastMsg == 0L ? -1L : (System.currentTimeMillis() - lastMsg);
        return new ConnectionStatus(
                sidecarConnected,
                fyersUpstreamConnected.get(),
                sidecarConnected ? fyersUpstreamStatus : FyersUpstreamStatus.UNKNOWN,
                fyersLastErrorMessage,
                reconnectAttempts.get(),
                sinceLast);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        lastMessageAtMillis.set(System.currentTimeMillis());
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());

            if (root.has("type")) {
                String type = root.get("type").asText();

                switch (type) {
                    case "history" -> handleHistoryMessage(root);
                    case "history_complete" -> handleHistoryCompleteMessage(root);
                    case "tick" -> handleTickMessage(root);
                    case "heartbeat" -> handleHeartbeatMessage(root);
                    default -> log.warn("Unknown message type received: {}", type);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse payload from sidecar: {}", e.getMessage());
        }
    }

    private void handleHeartbeatMessage(JsonNode root) {
        boolean fyersConnected = root.path("fyersConnected").asBoolean(false);
        boolean wasConnected = fyersUpstreamConnected.getAndSet(fyersConnected);

        // "fyersStatus" / "fyersLastError" are richer fields; fall back gracefully
        // if talking to an older sidecar that only sends "fyersConnected".
        FyersUpstreamStatus previousStatus = fyersUpstreamStatus;
        FyersUpstreamStatus newStatus = parseUpstreamStatus(root, fyersConnected);
        fyersUpstreamStatus = newStatus;

        JsonNode errorNode = root.get("fyersLastError");
        fyersLastErrorMessage = (errorNode != null && !errorNode.isNull() && errorNode.has("message"))
                ? errorNode.get("message").asText(null)
                : null;

        if (newStatus != previousStatus) {
            switch (newStatus) {
                case AUTH_EXPIRED -> log.warn("FYERS upstream reports the auth token has expired " +
                        "(sidecar link to Java is still up). Re-authenticate the sidecar (refresh the " +
                        "FYERS access token); no Java restart is needed. Detail: {}", fyersLastErrorMessage);
                case DISCONNECTED -> log.warn("FYERS upstream disconnected (sidecar link to Java is still up). " +
                        "Detail: {}", fyersLastErrorMessage);
                case CONNECTED -> log.info("FYERS upstream connected - live data flowing again.");
                default -> log.debug("Sidecar heartbeat received. FYERS status: {}", newStatus);
            }
        } else {
            log.debug("Sidecar heartbeat received. FYERS status: {}", newStatus);
        }
    }

    private FyersUpstreamStatus parseUpstreamStatus(JsonNode root, boolean fyersConnected) {
        JsonNode statusNode = root.get("fyersStatus");
        if (statusNode != null && !statusNode.isNull()) {
            try {
                return FyersUpstreamStatus.valueOf(statusNode.asText());
            } catch (IllegalArgumentException e) {
                log.debug("Unrecognized fyersStatus value from sidecar: {}", statusNode.asText());
            }
        }
        // Older sidecar without fyersStatus: best effort from the boolean alone.
        return fyersConnected ? FyersUpstreamStatus.CONNECTED : FyersUpstreamStatus.DISCONNECTED;
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
        if (!running.compareAndSet(false, true)) {
            log.warn("start() called but sidecar tick source is already running");
            return;
        }
        client = new StandardWebSocketClient();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fyers-sidecar-connection");
            t.setDaemon(true);
            return t;
        });
        reconnectAttempts.set(0);
        connect();
        watchdogTask = executor.scheduleAtFixedRate(
                this::checkForStaleConnection, WATCHDOG_INTERVAL_MILLIS, WATCHDOG_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (watchdogTask != null) {
            watchdogTask.cancel(false);
        }
        WebSocketSession session = currentSession;
        if (session != null && session.isOpen()) {
            try {
                session.close(CloseStatus.NORMAL);
            } catch (Exception e) {
                log.debug("Error closing sidecar session during shutdown: {}", e.getMessage());
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void connect() {
        if (!running.get()) return;
        if (!connectInFlight.compareAndSet(false, true)) {
            log.debug("Connect already in flight, skipping duplicate attempt");
            return;
        }

        log.info("Connecting to sidecar at {} (attempt {})", sidecarUrl, reconnectAttempts.get() + 1);
        try {
            // StandardWebSocketClient#execute is asynchronous: a connection
            // failure (refused, DNS, timeout) surfaces as a *failed future*,
            // not a thrown exception, so it must be handled here rather than
            // relying on a surrounding try/catch alone.
            client.execute(this, sidecarUrl).whenComplete((session, error) -> {
                connectInFlight.set(false);
                if (error != null) {
                    log.error("Failed to connect to sidecar: {}", error.getMessage());
                    scheduleReconnect();
                }
                // On success, afterConnectionEstablished() handles bookkeeping.
            });
        } catch (Exception e) {
            // Defensive: covers any synchronous failure (e.g. malformed URI)
            // that execute() might still throw directly.
            connectInFlight.set(false);
            log.error("Failed to initiate connection to sidecar: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) return;
        int attempt = reconnectAttempts.getAndIncrement();
        long delay = Math.min(baseReconnectDelayMillis * (1L << Math.min(attempt, 10)), maxReconnectDelayMillis);
        log.info("Scheduling sidecar reconnect in {} ms (attempt {})", delay, attempt + 1);
        executor.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Detects a "zombie" connection: the OS socket is still reported open but
     * no messages (including heartbeats) have arrived for longer than
     * expected. A dead connection like this may never fire
     * afterConnectionClosed on its own, so we force it shut and let the
     * normal close-triggered reconnect path take over.
     */
    private void checkForStaleConnection() {
        if (!running.get()) return;
        WebSocketSession session = currentSession;
        if (session == null || !session.isOpen()) return;

        long lastMsg = lastMessageAtMillis.get();
        if (lastMsg == 0L) return; // haven't received a first message yet, give it time

        long silence = System.currentTimeMillis() - lastMsg;
        if (silence > staleConnectionTimeoutMillis) {
            log.warn("No messages from sidecar in {} ms (limit {} ms) - treating connection as stale and forcing reconnect",
                    silence, staleConnectionTimeoutMillis);
            try {
                session.close(CloseStatus.GOING_AWAY);
            } catch (Exception e) {
                log.debug("Error force-closing stale sidecar session: {}", e.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Connected to sidecar");
        currentSession = session;
        reconnectAttempts.set(0);
        lastMessageAtMillis.set(System.currentTimeMillis());
        fyersUpstreamConnected.set(false); // unknown until the first heartbeat confirms it
        fyersUpstreamStatus = FyersUpstreamStatus.UNKNOWN;
        fyersLastErrorMessage = null;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("Sidecar connection closed: {}", status);
        currentSession = null;
        fyersUpstreamConnected.set(false);
        fyersUpstreamStatus = FyersUpstreamStatus.UNKNOWN;
        connectInFlight.set(false);
        scheduleReconnect();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Sidecar transport error: {}", exception.getMessage());
        // Force the session closed so afterConnectionClosed() fires and
        // reconnection is scheduled from a single, consistent place. Spring
        // usually calls afterConnectionClosed() after a transport error, but
        // relying on "usually" is exactly the gap that let this go silently
        // undetected before, so we close explicitly rather than assume.
        if (session != null && session.isOpen()) {
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception e) {
                log.debug("Error closing sidecar session after transport error: {}", e.getMessage());
            }
        }
    }
}