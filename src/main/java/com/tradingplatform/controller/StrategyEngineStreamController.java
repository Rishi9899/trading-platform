package com.tradingplatform.controller;

import com.tradingplatform.domain.candle.Candle;
import com.tradingplatform.strategy.StrategyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.*;

@RestController
@RequestMapping("/visualizer")
public class StrategyEngineStreamController {

    private static final Logger log = LoggerFactory.getLogger(StrategyEngineStreamController.class);

    // No server-side timeout - the emitter's own onCompletion/onTimeout/onError
    // callbacks are what detach the listener, so this just means "stay open
    // until the client goes away", matching the old sink's behaviour.
    private static final long NO_TIMEOUT = 0L;

    private final StrategyEngine strategyEngine;

    public StrategyEngineStreamController(StrategyEngine strategyEngine) {
        this.strategyEngine = strategyEngine;
    }

    /**
     * Historical candles endpoint pulled directly from StrategyEngine memory
     */
    @GetMapping("/api/history")
    public List<Map<String, Object>> getEngineHistory(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "5m") String timeframe) {

        List<Candle> history = strategyEngine.getHistorySnapshot(symbol, timeframe);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Candle c : history) {
            result.add(formatCandle(c));
        }
        return result;
    }

    /**
     * SSE stream broadcasting live closed candles for one symbol+timeframe.
     *
     * Uses SseEmitter (Spring MVC's own servlet-async SSE type) rather than a
     * shared reactive Flux/Sinks.Many. Each browser connection gets its own
     * emitter and its own StrategyEngine.CandleStreamListener, registered and
     * torn down together - when the emitter completes (client disconnects,
     * times out, or errors), the SAME callback detaches the listener from
     * StrategyEngine, so there is no window where a background thread can
     * still be pushing candles at an AsyncContext Tomcat has already closed.
     * That's what the previous reactive-Flux implementation couldn't
     * guarantee, and it is exactly what showed up as
     * "attempted to use the AsyncContext after an error had occurred".
     */
    @GetMapping(value = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEngineCandles(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "5m") String timeframe) {

        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);

        StrategyEngine.CandleStreamListener listener = candle -> {
            if (!candle.getSymbol().equalsIgnoreCase(symbol) || !candle.getTimeframe().equalsIgnoreCase(timeframe)) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name("candle").data(formatCandle(candle)));
            } catch (IOException | IllegalStateException e) {
                // Client is already gone or the emitter already completed - this
                // is the normal "browser closed the tab" path, not a real error.
                // completeWithError just runs cleanup below; it does not rethrow
                // or touch Tomcat's AsyncContext directly.
                emitter.completeWithError(e);
            }
        };

        strategyEngine.addCandleStreamListener(listener);

        Runnable detach = () -> strategyEngine.removeCandleStreamListener(listener);
        emitter.onCompletion(detach);
        emitter.onTimeout(detach);
        emitter.onError(e -> {
            log.debug("SSE stream error for {}/{}: {}", symbol, timeframe, e.getMessage());
            detach.run();
        });

        return emitter;
    }

    private Map<String, Object> formatCandle(Candle c) {
        Map<String, Object> map = new HashMap<>();

        long epochSeconds;
        try {
            epochSeconds = c.getWindowStart().getEpochSecond();
        } catch (Exception e) {
            epochSeconds = ((TemporalAccessor) c.getWindowStart())
                    .getLong(ChronoField.INSTANT_SECONDS);
        }

        map.put("time", epochSeconds);
        map.put("open", c.getOpen());
        map.put("high", c.getHigh());
        map.put("low", c.getLow());
        map.put("close", c.getClose());
        map.put("volume", c.getVolume());
        return map;
    }
}