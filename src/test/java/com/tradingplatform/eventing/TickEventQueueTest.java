package com.tradingplatform.eventing;

import com.tradingplatform.domain.tick.Tick;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TickEventQueueTest {

    @Test
    void deliversTicksToListenerInOrder() throws InterruptedException {
        TickEventQueue queue = new TickEventQueue(100);
        List<Tick> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(5);

        queue.addListener(tick -> {
            received.add(tick);
            latch.countDown();
        });
        queue.start();

        for (int i = 0; i < 5; i++) {
            queue.onTick(tick("NIFTY", 100 + i));
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS), "all ticks should be delivered");
        assertEquals(5, received.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(new BigDecimal(100 + i), received.get(i).getPrice());
        }

        queue.stop();
    }

    @Test
    void onTickNeverBlocksTheProducerEvenWhenQueueIsFull() {
        // Tiny capacity, no consumer started - onTick must still return
        // immediately rather than blocking the calling (producer) thread.
        TickEventQueue queue = new TickEventQueue(2);

        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            queue.onTick(tick("NIFTY", 100 + i));
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 1000,
                "onTick must never block the producer, even when the queue is full");
        assertTrue(queue.getDroppedCount() > 0, "excess ticks should be counted as dropped");
    }

    @Test
    void oneListenerThrowingDoesNotStopProcessingForOthers() throws InterruptedException {
        TickEventQueue queue = new TickEventQueue(100);
        CountDownLatch latch = new CountDownLatch(3);

        queue.addListener(tick -> {
            throw new RuntimeException("simulated failure in one listener");
        });
        queue.addListener(tick -> latch.countDown());
        queue.start();

        queue.onTick(tick("NIFTY", 100));
        queue.onTick(tick("NIFTY", 101));
        queue.onTick(tick("NIFTY", 102));

        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "the healthy listener should keep receiving ticks despite the other one throwing");

        queue.stop();
    }

    private static Tick tick(String symbol, int price) {
        return new Tick(symbol, Instant.now(), new BigDecimal(price), 10L);
    }
}