# Trading Platform — Phase 1

Project foundation + a fake tick generator feeding a candle builder, so the
core data-flow pattern (`TickSource → TickListener → CandleListener`) exists
and is testable before FYERS or PostgreSQL enter the picture.

## Structure

```
com.tradingplatform
├── TradingPlatformApplication   Spring Boot entry point
├── controller/HealthController  GET /api/health
├── domain/tick/Tick              immutable tick DTO
├── domain/candle/Candle          immutable completed-candle DTO
├── marketdata/
│   ├── TickSource                 interface: anything that produces ticks
│   ├── TickListener                interface: anything that consumes ticks
│   └── FakeTickGenerator          TickSource impl - random walk, no network
├── candle/
│   ├── CandleBuilder               TickListener impl - buckets ticks into windows
│   ├── CandleListener               interface: anything notified on candle close
│   └── LoggingCandleListener       CandleListener impl - just prints
└── config/MarketDataPipelineRunner  wires the above together at startup
```

## Why FakeTickGenerator instead of FYERS first

FYERS requires OAuth setup and only streams during market hours. Everything
downstream of a tick (candle building, later the strategy engine) doesn't
care where the tick came from — that's the point of the `TickSource`
interface. Building against a fake source lets us develop and test 24/7.
When we integrate FYERS, `FyersWebSocketTickSource` will implement the same
`TickSource` interface and drop in without touching `CandleBuilder`.

## Run it

```bash
cd trading-platform
mvn spring-boot:run
```

## Verify it

1. **App boots**: check for `Started TradingPlatformApplication` in the logs.
2. **Health check**: `curl http://localhost:8080/api/health` → `{"status":"UP",...}`
3. **Pipeline runs**: within ~10 seconds of startup (per `application.yml`,
   candles are set to a dev-friendly 10-second window) you should see lines like:

   ```
   Starting Phase 1 pipeline: symbols=[NIFTY, BANKNIFTY] tickIntervalMillis=500 candleTimeframeSeconds=10
   [CANDLE CLOSED] Candle{symbol='NIFTY', window=2026-08-22T10:15:00Z->2026-08-22T10:15:10Z, O=20134.50 H=20141.20 L=20128.10 C=20135.00, vol=2450}
   [CANDLE CLOSED] Candle{symbol='BANKNIFTY', window=2026-08-22T10:15:00Z->2026-08-22T10:15:10Z, O=...}
   ```

   New candles should print roughly every 10 seconds, once per symbol.

## Run the tests

```bash
mvn test
```

`CandleBuilderTest` exercises the boundary logic directly (no Spring context,
no network) — same-window aggregation, per-symbol isolation, and dropping of
late/out-of-order ticks. These are the cases that actually matter once real
market data (which won't be as clean as the fake generator) shows up.

## Note on config

`application.yml` uses fast dev values (10s candles, 500ms ticks) purely so
you can see output quickly. Real timeframes (1m/5m/15m) get introduced
properly in Phase 5 — this is a placeholder, not the final design.

## Next: Phase 2 — PostgreSQL

Set up the database and the first entities (`Strategy`, `StrategyInstance`,
`Signal`, `Trade`, `Performance`), and prove `INSERT`/`SELECT` works from
Spring Boot before anything else depends on persistence.
