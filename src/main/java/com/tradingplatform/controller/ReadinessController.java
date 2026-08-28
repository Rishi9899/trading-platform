package com.tradingplatform.controller;

import com.tradingplatform.domain.readiness.ReadinessSnapshot;
import com.tradingplatform.readiness.ReadinessService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/ui/api")
public class ReadinessController {

    private final ReadinessService readinessService;

    public ReadinessController(ReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    /**
     * Get readiness for specific symbol/timeframe
     * Returns empty snapshot if no data available yet (instead of 404)
     */
    @GetMapping("/signal-readiness")
    public ResponseEntity<ReadinessSnapshot> getReadiness(
            @RequestParam String symbol,
            @RequestParam String timeframe) {

        return ResponseEntity.ok(
                readinessService.getReadiness(symbol, timeframe)
                        .orElse(createEmptySnapshot(symbol, timeframe))
        );
    }

    /**
     * Get all readiness snapshots
     */
    @GetMapping("/signal-readiness/all")
    public Map<String, ReadinessSnapshot> getAllReadiness() {
        return readinessService.getAllReadiness();
    }

    /**
     * Create an empty snapshot when no data is available yet
     */
    private ReadinessSnapshot createEmptySnapshot(String symbol, String timeframe) {
        return new ReadinessSnapshot(
                symbol,
                timeframe,
                0,  // readinessPercent
                0,  // currentVotes
                3,  // requiredVotes
                "HOLD",  // signal
                0.0,  // agreementScore
                Collections.emptyList(),  // blockers
                "Waiting for first candle close...",  // nearestTrigger
                Instant.now()  // updatedAt
        );
    }
}