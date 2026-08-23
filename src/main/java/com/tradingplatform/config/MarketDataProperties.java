package com.tradingplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Binds the "app.*" section of application.yml. Using @ConfigurationProperties
 * instead of @Value here because @Value cannot resolve YAML list syntax.
 */
@ConfigurationProperties(prefix = "app")
public class MarketDataProperties {

    private Tick tick = new Tick();
    private Candle candle = new Candle();
    private Fyers fyers = new Fyers(); // Added Fyers root property
    private Queue queue = new Queue();

    public Queue getQueue() {
        return queue;
    }

    public void setQueue(Queue queue) {
        this.queue = queue;
    }

    public static class Queue {
        private int capacity = 1000;

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }
    }

    public Tick getTick() {
        return tick;
    }

    public void setTick(Tick tick) {
        this.tick = tick;
    }

    public Candle getCandle() {
        return candle;
    }

    public void setCandle(Candle candle) {
        this.candle = candle;
    }

    public Fyers getFyers() {
        return fyers;
    }

    public void setFyers(Fyers fyers) {
        this.fyers = fyers;
    }

    public static class Tick {
        private List<String> symbols;
        private long intervalMillis;
        private String source = "fake"; // "fake" or "fyers-sidecar"

        public List<String> getSymbols() {
            return symbols;
        }

        public void setSymbols(List<String> symbols) {
            this.symbols = symbols;
        }

        public long getIntervalMillis() {
            return intervalMillis;
        }

        public void setIntervalMillis(long intervalMillis) {
            this.intervalMillis = intervalMillis;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }
    }

    public static class Candle {
        private long timeframeSeconds;
        private List<String> derivedTimeframes = new java.util.ArrayList<>();

        public long getTimeframeSeconds() {
            return timeframeSeconds;
        }

        public void setTimeframeSeconds(long timeframeSeconds) {
            this.timeframeSeconds = timeframeSeconds;
        }

        public List<String> getDerivedTimeframes() {
            return derivedTimeframes;
        }

        public void setDerivedTimeframes(List<String> derivedTimeframes) {
            this.derivedTimeframes = derivedTimeframes;
        }
    }

    // Added nested Fyers configuration
    public static class Fyers {
        private Sidecar sidecar = new Sidecar();

        public Sidecar getSidecar() {
            return sidecar;
        }

        public void setSidecar(Sidecar sidecar) {
            this.sidecar = sidecar;
        }

        public static class Sidecar {
            private String url = "ws://localhost:8765";
            private long reconnectDelayMillis = 5000;

            public String getUrl() {
                return url;
            }

            public void setUrl(String url) {
                this.url = url;
            }

            public long getReconnectDelayMillis() {
                return reconnectDelayMillis;
            }

            public void setReconnectDelayMillis(long reconnectDelayMillis) {
                this.reconnectDelayMillis = reconnectDelayMillis;
            }
        }
    }
}