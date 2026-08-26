package com.tradingplatform.evaluation;

import com.tradingplatform.domain.performance.Performance;
import com.tradingplatform.domain.performance.PerformanceRepository;
import com.tradingplatform.domain.strategy.StrategyInstance;
import com.tradingplatform.domain.strategy.StrategyInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;

/**
 * @Component  → Spring creates this bean automatically at startup, injects constructor args.
 * @Scheduled  → Spring calls flushPerformance() every 5 minutes (only works because
 *               @EnableScheduling is on the main app class).
 *
 * Without @Component: Spring doesn't know this class exists → no bean → scheduler never runs.
 * Without @EnableScheduling: Spring ignores all @Scheduled annotations → method never fires.
 */
@Component
public class PerformanceFlushScheduler {

    private static final Logger log = LoggerFactory.getLogger(PerformanceFlushScheduler.class);

    private final PerformanceTrackingSignalListener performanceTracker;
    private final PerformanceRepository performanceRepository;
    private final StrategyInstanceRepository strategyInstanceRepository;

    public PerformanceFlushScheduler(PerformanceTrackingSignalListener performanceTracker,
                                     PerformanceRepository performanceRepository,
                                     StrategyInstanceRepository strategyInstanceRepository) {
        this.performanceTracker = performanceTracker;
        this.performanceRepository = performanceRepository;
        this.strategyInstanceRepository = strategyInstanceRepository;
    }

    /**
     * Runs every 5 minutes (300,000 ms). Reads in-memory performance stats
     * and snapshots them into the performance table for:
     * - ConfluenceEngine weight calculations (reads from this table)
     * - Dashboard/API queries
     * - Historical performance auditing
     */
    @Scheduled(fixedRate = 300_000)
    public void flushPerformance() {
        Map<Long, StrategyEvaluator> evaluators = performanceTracker.getEvaluators();

        if (evaluators.isEmpty()) return;

        Instant now = Instant.now();
        int flushed = 0;

        for (Map.Entry<Long, StrategyEvaluator> entry : evaluators.entrySet()) {
            Long instanceId = entry.getKey();
            StrategyPerformance perf = entry.getValue().getPerformance();

            if (perf.getTotalTrades() == 0) continue;

            StrategyInstance strategyInstance = strategyInstanceRepository.findById(instanceId).orElse(null);
            if (strategyInstance == null) {
                log.warn("PerformanceFlush: StrategyInstance {} not found in DB, skipping.", instanceId);
                continue;
            }

            // Win rate as 0.0 to 1.0 (not percentage)
            BigDecimal winRate = BigDecimal.valueOf(perf.getWinningTrades())
                    .divide(BigDecimal.valueOf(perf.getTotalTrades()), 4, RoundingMode.HALF_UP);

            Performance snapshot = new Performance(
                    strategyInstance,
                    now,
                    perf.getTotalTrades(),
                    winRate,
                    perf.getNetProfit(),
                    perf.getMaxDrawdown()
            );

            performanceRepository.save(snapshot);
            flushed++;
        }

        if (flushed > 0) {
            log.info("PerformanceFlush: Saved {} performance snapshots.", flushed);
        }
    }
}