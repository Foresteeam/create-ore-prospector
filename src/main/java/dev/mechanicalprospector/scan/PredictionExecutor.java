package dev.mechanicalprospector.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** A server-owned, bounded CPU pool. Backpressure never runs work on the calling game thread. */
public final class PredictionExecutor implements AutoCloseable {
    private static final AtomicInteger POOL_SEQUENCE = new AtomicInteger();
    private final ThreadPoolExecutor executor;
    private final Set<Batch<?>> outstanding = ConcurrentHashMap.newKeySet();
    private final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
    private final AtomicLong completed = new AtomicLong();
    private volatile boolean closed;
    public PredictionExecutor(int threads, int queuedBatches) {
        if (threads < 1 || queuedBatches < 1) throw new IllegalArgumentException("Positive pool bounds required");
        int poolId = POOL_SEQUENCE.incrementAndGet(); var sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queuedBatches), runnable -> {
                    var thread = new Thread(runnable, "prospector-predict-" + poolId + "-" + sequence.incrementAndGet());
                    thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    public int threads() { return executor.getCorePoolSize(); }
    public int activeBatches() { return active.get(); }
    public int peakActiveBatches() { return peak.get(); }
    public long completedJobs() { return completed.get(); }
    public int queuedBatches() { return executor.getQueue().size(); }
    /** One completion fence per batch, rather than a contended future/map entry for every chunk. */
    public static final class Batch<T> {
        private record Outcome<T>(T value, Throwable error) {}
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final List<Outcome<T>> outcomes;
        private final int size;
        private Batch(int size) { this.size = size; outcomes = new ArrayList<>(size); }
        public int size() { return size; }
        public boolean isDone() { return completion.isDone(); }
        public CompletableFuture<Void> completion() { return completion; }
        public T join(int index) {
            completion.join(); var result = outcomes.get(index);
            if (result.error != null) throw new CompletionException(result.error);
            return result.value;
        }
    }
    /** Returns null if full/closed; the caller retains the jobs and retries on a later tick. */
    public synchronized <T> Batch<T> trySubmitBatch(List<? extends Supplier<T>> jobs) {
        if (closed) return null;
        if (jobs.isEmpty() || jobs.size() > 32) throw new IllegalArgumentException("Batch size must be 1..32");
        var copy = List.copyOf(jobs); var batch = new Batch<T>(copy.size()); outstanding.add(batch);
        try {
            executor.execute(() -> {
                int running = active.incrementAndGet(); peak.accumulateAndGet(running, Math::max);
                int successes = 0;
                try {
                    for (int i = 0; i < copy.size(); i++) {
                        if (closed || Thread.currentThread().isInterrupted()) { batch.completion.cancel(false); return; }
                        try { batch.outcomes.add(new Batch.Outcome<>(copy.get(i).get(), null)); successes++; }
                        catch (Throwable error) { batch.outcomes.add(new Batch.Outcome<>(null, error)); }
                    }
                    completed.addAndGet(successes); batch.completion.complete(null);
                } finally { active.decrementAndGet(); outstanding.remove(batch); }
            });
            return batch;
        } catch (RejectedExecutionException ignored) {
            outstanding.remove(batch); batch.completion.cancel(false);
            return null;
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (var batch : outstanding) batch.completion.completeExceptionally(new CancellationException("Prediction pool closed"));
        executor.shutdownNow(); outstanding.clear();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
