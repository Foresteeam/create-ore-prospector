package dev.mechanicalprospector.scan;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class PredictionExecutorTest {
    @Test void fourWorkersReallyRunInParallel() throws Exception {
        var started = new CountDownLatch(4); var release = new CountDownLatch(1);
        Set<String> threads = ConcurrentHashMap.newKeySet();
        try (var pool = new PredictionExecutor(4, 4)) {
            var futures = new ArrayList<PredictionExecutor.Batch<Integer>>();
            for (int i = 0; i < 4; i++) futures.add(pool.trySubmitBatch(List.of(() -> {
                threads.add(Thread.currentThread().getName()); started.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Parallel jobs failed to start"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                return 7;
            })));
            try { assertTrue(started.await(5, TimeUnit.SECONDS)); assertEquals(4, threads.size()); assertEquals(4, pool.peakActiveBatches()); }
            finally { release.countDown(); }
            for (var future : futures) { future.completion().get(5, TimeUnit.SECONDS); assertEquals(7, future.join(0)); }
        }
    }
    @Test void aFullPoolAppliesBackpressureWithoutRunningOnTheCaller() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = new PredictionExecutor(1, 1)) {
            Supplier<Integer> blocking = () -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                return 1;
            };
            var first = pool.trySubmitBatch(List.of(blocking)); assertTrue(started.await(5, TimeUnit.SECONDS));
            var second = pool.trySubmitBatch(List.of(() -> 2)); assertNotNull(second);
            var callerRan = new java.util.concurrent.atomic.AtomicBoolean();
            assertNull(pool.trySubmitBatch(List.of(() -> { callerRan.set(true); return 3; })));
            assertFalse(callerRan.get()); assertEquals(1, pool.queuedBatches());
            release.countDown(); first.completion().get(5, TimeUnit.SECONDS); second.completion().get(5, TimeUnit.SECONDS);
            assertEquals(1, first.join(0)); assertEquals(2, second.join(0));
        } finally { release.countDown(); }
    }
    @Test void shutdownCancelsActiveAndQueuedResultsAndRejectsNewJobs() throws Exception {
        var pool = new PredictionExecutor(1, 2); var started = new CountDownLatch(1);
        var active = pool.trySubmitBatch(List.of(() -> {
            started.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            return 1;
        }));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var queued = pool.trySubmitBatch(List.of(() -> 2));
        pool.close();
        assertTrue(active.completion().isCompletedExceptionally()); assertTrue(queued.completion().isCompletedExceptionally());
        assertNull(pool.trySubmitBatch(List.of(() -> 3))); assertEquals(0, pool.activeBatches());
        pool.close();
    }
    @Test void failuresAreIsolatedWithinABatch() throws Exception {
        try (var pool = new PredictionExecutor(2, 2)) {
            var results = pool.<Integer>trySubmitBatch(List.of(() -> 10, () -> { throw new IllegalStateException("bad input"); }, () -> 30));
            results.completion().get(5, TimeUnit.SECONDS);
            assertEquals(10, results.join(0));
            assertThrows(java.util.concurrent.CompletionException.class, () -> results.join(1));
            assertEquals(30, results.join(2));
            assertThrows(IllegalArgumentException.class, () -> pool.trySubmitBatch(List.of()));
        }
    }
}
