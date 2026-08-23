package com.tradingplatform.eventing;

import com.tradingplatform.domain.tick.Tick;
import com.tradingplatform.marketdata.TickListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sits between any TickSource and downstream consumers (CandleBuilder,
 * later the Strategy Engine). TickSources call onTick() on this like any
 * other TickListener - but instead of processing inline, this just
 * enqueues and returns immediately, so the producer thread (e.g. the
 * WebSocket I/O thread for the FYERS sidecar) is never blocked by slow
 * downstream work.
 *
 * A single dedicated consumer thread drains the queue and dispatches to
 * registered listeners in order. This keeps ordering guarantees simple:
 * one consumer thread means no risk of two ticks for the same symbol
 * being processed out of order.
 *
 * Backpressure policy: bounded queue, drop-newest-on-full. For live
 * market ticks, a stale queued tick is worse than a dropped one - we'd
 * rather skip and stay current than fall behind and process old prices.
 * This is a deliberate tradeoff, not an oversight; dropped ticks are
 * counted and logged so it's visible if it's happening often (which
 * would mean downstream processing is too slow, not that dropping itself
 * is wrong).
 */
public class TickEventQueue implements TickListener {

    private static final Logger log = LoggerFactory.getLogger(TickEventQueue.class);

    private final BlockingQueue<Tick> queue;
    private final List<TickListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong processedCount = new AtomicLong();

    private Thread consumerThread;

    public TickEventQueue(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    public void addListener(TickListener listener) {
        listeners.add(listener);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        consumerThread = new Thread(this::consumeLoop, "tick-event-queue-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    public void stop() {
        running.set(false);
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
    }

    @Override
    public void onTick(Tick tick) {
        boolean accepted = queue.offer(tick);
        if (!accepted) {
            long dropped = droppedCount.incrementAndGet();
            if (dropped % 100 == 1) {
                // Log every 100th drop rather than every single one - if
                // this is happening constantly, spamming logs won't help
                // and will itself add overhead.
                log.warn("Dropping ticks - queue full. Total dropped so far: {}. "
                        + "Downstream processing (CandleBuilder / strategies) may be too slow "
                        + "for the current tick rate.", dropped);
            }
        }
    }

    private void consumeLoop() {
        while (running.get()) {
            Tick tick;
            try {
                tick = queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (tick == null) {
                continue;
            }

            for (TickListener listener : listeners) {
                try {
                    listener.onTick(tick);
                } catch (Exception e) {
                    // Isolated per listener - one throwing must not prevent
                    // the others from receiving this same tick.
                    log.error("Listener threw while processing tick: {}", e.getMessage(), e);
                }
            }
            processedCount.incrementAndGet();
        }
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }

    public long getProcessedCount() {
        return processedCount.get();
    }

    public int getCurrentQueueSize() {
        return queue.size();
    }
}