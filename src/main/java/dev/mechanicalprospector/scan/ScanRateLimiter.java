package dev.mechanicalprospector.scan;

/** Monotonic, paced admissions: no accumulated burst after a pause or idle period. */
public final class ScanRateLimiter {
    private long nextNanos;
    private int lastRate;
    private boolean initialized;
    public boolean tryAcquire(long now, int perSecond) {
        if (perSecond < 1) throw new IllegalArgumentException("Rate must be positive");
        if (!initialized || lastRate != perSecond) {
            nextNanos = now; lastRate = perSecond; initialized = true;
        }
        long interval = (1_000_000_000L + perSecond - 1) / perSecond;
        // At most 50ms of catch-up; idle time must not fund a full-screen burst.
        long catchUp = Math.min(50_000_000L, interval * Math.max(0, perSecond / 20 - 1));
        if (nextNanos < now - catchUp) nextNanos = now - catchUp;
        if (now < nextNanos) return false;
        nextNanos += interval; return true;
    }
    public void reset() { initialized = false; }
}
