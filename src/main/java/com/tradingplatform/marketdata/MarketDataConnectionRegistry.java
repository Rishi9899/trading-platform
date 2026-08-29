package com.tradingplatform.marketdata;

import com.tradingplatform.marketdata.fyers.FyersSidecarTickSource;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The active {@link TickSource} is built at runtime inside
 * {@code MarketDataPipelineRunner} (it depends on config: "fake" vs
 * "fyers-sidecar"), so it isn't a Spring bean and can't just be
 * {@code @Autowired} elsewhere.
 *
 * This registry is the seam that lets Spring-managed consumers - like a
 * health endpoint - find out what's actually running and, when it's the
 * FYERS sidecar, ask it for connection status.
 */
@Component
public class MarketDataConnectionRegistry {

    private volatile String activeSourceType = "none";
    private volatile FyersSidecarTickSource fyersTickSource;

    public void registerFyersSidecar(FyersSidecarTickSource source) {
        this.fyersTickSource = source;
        this.activeSourceType = "fyers-sidecar";
    }

    public void registerFake() {
        this.fyersTickSource = null;
        this.activeSourceType = "fake";
    }

    public String getActiveSourceType() {
        return activeSourceType;
    }

    public Optional<FyersSidecarTickSource> getFyersTickSource() {
        return Optional.ofNullable(fyersTickSource);
    }
}