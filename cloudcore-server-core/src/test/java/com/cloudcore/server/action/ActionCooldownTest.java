package com.cloudcore.server.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ActionCooldownTest {

    private final AtomicLong now = new AtomicLong(1_000L);
    private final ActionCooldown cooldown = new ActionCooldown(now::get);

    @Test
    void blocksUntilTheWindowExpires() {
        assertTrue(cooldown.tryAcquire("player", "page/buy", 1_000L));
        assertFalse(cooldown.tryAcquire("player", "page/buy", 1_000L));

        now.addAndGet(999L);
        assertFalse(cooldown.tryAcquire("player", "page/buy", 1_000L));

        now.addAndGet(1L);
        assertTrue(cooldown.tryAcquire("player", "page/buy", 1_000L));
    }

    @Test
    void keysAndPlayersAreIndependent() {
        assertTrue(cooldown.tryAcquire("alice", "shop/buy", 5_000L));
        assertTrue(cooldown.tryAcquire("alice", "bank/withdraw", 5_000L));
        assertTrue(cooldown.tryAcquire("bob", "shop/buy", 5_000L));
    }

    @Test
    void zeroCooldownNeverBlocks() {
        assertTrue(cooldown.tryAcquire("player", "key", 0L));
        assertTrue(cooldown.tryAcquire("player", "key", 0L));
        assertEquals(0L, cooldown.remainingMillis("player", "key"));
    }

    @Test
    void reportsRemainingTimeAndForgetsOnQuit() {
        cooldown.tryAcquire("player", "key", 2_000L);
        assertEquals(2_000L, cooldown.remainingMillis("player", "key"));

        now.addAndGet(500L);
        assertEquals(1_500L, cooldown.remainingMillis("player", "key"));

        cooldown.forget("player");
        assertEquals(0L, cooldown.remainingMillis("player", "key"));
        assertTrue(cooldown.tryAcquire("player", "key", 2_000L));
    }
}
