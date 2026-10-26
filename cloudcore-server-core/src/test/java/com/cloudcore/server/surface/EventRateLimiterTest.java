package com.cloudcore.server.surface;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class EventRateLimiterTest {

    @Test
    void burstIsConsumedThenRefused() {
        AtomicLong clock = new AtomicLong(0);
        EventRateLimiter limiter = new EventRateLimiter(20, 5, clock::get);
        for (int index = 0; index < 5; index++) {
            assertTrue(limiter.tryAcquire("player"));
        }
        assertFalse(limiter.tryAcquire("player"));
    }

    @Test
    void tokensRefillOverTime() {
        AtomicLong clock = new AtomicLong(0);
        EventRateLimiter limiter = new EventRateLimiter(20, 5, clock::get);
        for (int index = 0; index < 5; index++) {
            limiter.tryAcquire("player");
        }
        clock.addAndGet(100); // 100ms at 20/s = 2 tokens
        assertTrue(limiter.tryAcquire("player"));
        assertTrue(limiter.tryAcquire("player"));
        assertFalse(limiter.tryAcquire("player"));
    }

    @Test
    void bucketsArePerPlayer() {
        AtomicLong clock = new AtomicLong(0);
        EventRateLimiter limiter = new EventRateLimiter(20, 1, clock::get);
        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("b"));
        limiter.forget("a");
        assertTrue(limiter.tryAcquire("a"));
    }

    @Test
    void burstCannotExceedItsCapAfterLongIdle() {
        AtomicLong clock = new AtomicLong(0);
        EventRateLimiter limiter = new EventRateLimiter(20, 3, clock::get);
        clock.addAndGet(60_000);
        assertTrue(limiter.tryAcquire("p"));
        assertTrue(limiter.tryAcquire("p"));
        assertTrue(limiter.tryAcquire("p"));
        assertFalse(limiter.tryAcquire("p"));
    }
}
