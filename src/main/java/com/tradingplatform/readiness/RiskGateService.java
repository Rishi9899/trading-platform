package com.tradingplatform.readiness;

import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks risk gates that block trade execution
 */
@Service
public class RiskGateService {

    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    public List<String> checkBlockers(String symbol, String timeframe) {
        List<String> blockers = new ArrayList<>();

        // Check market hours for NSE symbols
        if (symbol.startsWith("NSE:") && !isMarketOpen()) {
            blockers.add("session_closed");
        }

        // Check MCX hours (9:00 AM - 11:30 PM IST)
        if (symbol.startsWith("MCX:") && !isMcxOpen()) {
            blockers.add("session_closed");
        }

        return blockers;
    }

    private boolean isMarketOpen() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        return !now.isBefore(MARKET_OPEN) && !now.isAfter(MARKET_CLOSE);
    }

    private boolean isMcxOpen() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        return !now.isBefore(LocalTime.of(9, 0)) && !now.isAfter(LocalTime.of(23, 30));
    }
}