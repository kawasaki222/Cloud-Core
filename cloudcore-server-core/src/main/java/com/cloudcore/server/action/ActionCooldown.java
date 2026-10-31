package com.cloudcore.server.action;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Per-player, per-key action cooldowns.
 *
 * <p>The event rate limiter caps how often a client can click; this caps how often one named
 * action may actually run. It backs the built-in {@code cooldown: {key, ticks}} action and
 * the page action property {@code cooldown_ticks}. Keys are scoped to the page that declared
 * them, so two pages may both use {@code "buy"} without blocking each other.
 *
 * <p>Time is injected in milliseconds so tests do not sleep. Not thread-safe on its own:
 * the server calls it on the main thread, and {@link #clear()} on quit.
 */
public final class ActionCooldown {

    private final LongSupplier clockMillis;
    private final Map<Object, Map<String, Long>> deadlines = new HashMap<>();

    public ActionCooldown() {
        this(System::currentTimeMillis);
    }

    public ActionCooldown(LongSupplier clockMillis) {
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /**
     * Runs {@code action} only when its key is off cooldown, then starts the cooldown.
     *
     * @return true when the action may run
     */
    public boolean tryAcquire(Object playerId, String key, long cooldownMillis) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(key, "key");
        if (cooldownMillis <= 0) {
            return true;
        }
        Map<String, Long> keys = deadlines.computeIfAbsent(playerId,
                ignored -> new HashMap<>());
        long now = clockMillis.getAsLong();
        Long deadline = keys.get(key);
        if (deadline != null && now < deadline) {
            return false;
        }
        keys.put(key, now + cooldownMillis);
        return true;
    }

    /** Remaining time in milliseconds, or 0 when the key is ready. */
    public long remainingMillis(Object playerId, String key) {
        Map<String, Long> keys = deadlines.get(playerId);
        if (keys == null) {
            return 0L;
        }
        Long deadline = keys.get(key);
        return deadline == null ? 0L : Math.max(0L, deadline - clockMillis.getAsLong());
    }

    /** Drops one player's cooldowns (called on quit). */
    public void forget(Object playerId) {
        deadlines.remove(playerId);
    }

    public void clear() {
        deadlines.clear();
    }
}
