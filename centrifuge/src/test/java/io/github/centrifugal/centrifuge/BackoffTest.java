package io.github.centrifugal.centrifuge;

import org.junit.Test;

import io.github.centrifugal.centrifuge.internal.backoff.Backoff;

import static org.junit.Assert.*;

public class BackoffTest {

    @Test
    public void testDurationWithinBounds() {
        Backoff backoff = new Backoff();
        int minDelay = 500;
        int maxDelay = 20000;
        for (int step = 0; step < 40; step++) {
            long duration = backoff.duration(step, minDelay, maxDelay);
            assertTrue("Duration should be >= minDelay, got " + duration, duration >= minDelay);
            assertTrue("Duration should be <= maxDelay, got " + duration, duration <= maxDelay);
        }
    }

    @Test
    public void testDurationNeverExceedsMax() {
        Backoff backoff = new Backoff();
        int minDelay = 100;
        int maxDelay = 5000;
        // Run many iterations to account for randomness.
        for (int i = 0; i < 1000; i++) {
            long duration = backoff.duration(20, minDelay, maxDelay);
            assertTrue("Duration should be <= maxDelay", duration <= maxDelay);
            assertTrue("Duration should be >= minDelay", duration >= minDelay);
        }
    }

    @Test
    public void testStepCappedAt31() {
        Backoff backoff = new Backoff();
        int minDelay = 100;
        int maxDelay = Integer.MAX_VALUE;
        // Steps beyond 31 should behave the same as step 31.
        // We can't test exact values due to randomness, but we can verify no overflow.
        for (int step = 32; step < 50; step++) {
            long duration = backoff.duration(step, minDelay, maxDelay);
            assertTrue("Duration should be >= minDelay", duration >= minDelay);
        }
    }

    @Test
    public void testZeroStep() {
        Backoff backoff = new Backoff();
        int minDelay = 500;
        int maxDelay = 20000;
        // At step 0, min = Math.min(maxDelay, minDelay * 2^0) = minDelay.
        // val = random in [0, minDelay], so duration = minDelay + val, capped at maxDelay.
        for (int i = 0; i < 100; i++) {
            long duration = backoff.duration(0, minDelay, maxDelay);
            assertTrue("Duration at step 0 should be >= minDelay", duration >= minDelay);
            // At step 0: max possible = minDelay + minDelay = 2*minDelay = 1000.
            assertTrue("Duration at step 0 should be <= 2*minDelay", duration <= 2 * minDelay);
        }
    }
}
