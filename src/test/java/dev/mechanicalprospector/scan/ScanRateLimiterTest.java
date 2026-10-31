package dev.mechanicalprospector.scan;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScanRateLimiterTest {
    @Test void enforcesLowRatesWithFractionalTickAllowance() {
        for (int rate : new int[]{1, 7, 20, 21, 99, 2048, 65536}) {
            var limiter = new ScanRateLimiter(); int count = 0;
            for (long time = 0; time < 1_000_000_000L; time += 50_000_000L)
                while (limiter.tryAcquire(time, rate)) count++;
            assertTrue(count > 0 && count <= rate, "rate=" + rate + " actual=" + count);
        }
    }
    @Test void cannotAccumulateAnIdleSecondIntoOneTick() {
        var limiter = new ScanRateLimiter(); assertTrue(limiter.tryAcquire(0, 2048));
        int count = 0;
        while (limiter.tryAcquire(600_000_000_000L, 2048)) count++;
        assertTrue(count <= 103, "An idle pause must not create a 2048-record burst");
    }
    @Test void sharesOneBudgetBetweenConsumersAndHonorsRateChanges() {
        var limiter = new ScanRateLimiter(); int one = 0, two = 0;
        for (long time = 0; time < 1_000_000_000L; time += 50_000_000L) {
            if (limiter.tryAcquire(time, 20)) one++;
            if (limiter.tryAcquire(time, 20)) two++;
        }
        assertTrue(one + two <= 20);
        assertTrue(limiter.tryAcquire(1_000_000_000L, 1));
        assertFalse(limiter.tryAcquire(1_050_000_000L, 1));
        assertTrue(limiter.tryAcquire(2_000_000_000L, 1));
        limiter.reset(); assertTrue(limiter.tryAcquire(0, 1));
    }
    @Test void irregularTicksNeverCreateAnUnboundedCatchUpBurst() {
        var limiter = new ScanRateLimiter(); long time = 0;
        for (int i = 0; i < 100; i++) {
            time += i % 3 == 0 ? 300_000_000L : 17_000_000L;
            int count = 0; while (limiter.tryAcquire(time, 1000)) count++;
            assertTrue(count <= 50);
        }
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(0, 0));
    }
}
