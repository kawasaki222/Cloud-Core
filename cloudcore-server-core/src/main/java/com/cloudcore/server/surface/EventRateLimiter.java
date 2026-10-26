package com.cloudcore.server.surface;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token-bucket limiter for client {@code EVENT} frames, one bucket per player.
 *
 * <p>Defaults follow docs/07-security.md: a steady rate of 20 events per second with a burst
 * of 40. Exceeding the bucket returns {@code false} and the caller answers
 * {@code EVENT_RESULT(RATE_LIMITED)} instead of running actions.
 *
 * <p>Thread-safe; the clock is injectable so tests do not sleep.
 */
public final class EventRateLimiter {
    /** Default events per second. */
    public static final int DEFAULT_RATE = 20;
    /** Default burst size. */
    public static final int DEFAULT_BURST = 40;

    private final int rate;
    private final int burst;
    private final LongSupplier clockMillis;
    private final ConcurrentHashMap<Object, Bucket> buckets = new ConcurrentHashMap<>();

    public EventRateLimiter() {
        this(DEFAULT_RATE, DEFAULT_BURST, System::currentTimeMillis);
    }

    public EventRateLimiter(int rate, int burst, LongSupplier clockMillis) {
        if (rate <= 0 || burst <= 0) {
            throw new IllegalArgumentException("rate and burst must be positive");
        }
        this.rate = rate;
        this.burst = burst;
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    private static final class Bucket {
        private double tokens;
        private long lastMillis;

        private Bucket(double tokens, long lastMillis) {
            this.tokens = tokens;
            this.lastMillis = lastMillis;
        }
    }

    /** @return true when the event may run */
    public boolean tryAcquire(Object playerId) {
        Objects.requireNonNull(playerId, "playerId");
        long now = clockMillis.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(playerId,
                ignored -> new Bucket(burst, now));
        synchronized (bucket) {
            long elapsed = Math.max(0, now - bucket.lastMillis);
            bucket.tokens = Math.min(burst, bucket.tokens + elapsed * rate / 1000.0);
            bucket.lastMillis = now;
            if (bucket.tokens < 1.0) {
                return false;
            }
            bucket.tokens -= 1.0;
            return true;
        }
    }

    /** Drops a player's bucket (called on quit). */
    public void forget(Object playerId) {
        buckets.remove(playerId);
    }

    public void clear() {
        buckets.clear();
    }
}
