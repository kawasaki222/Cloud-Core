package com.cloudcore.server.surface;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandAllowlistTest {

    @Test
    void disabledConfigBlocksEverything() {
        assertFalse(CommandAllowlist.allows(false, List.of("say"), "say hi"));
    }

    @Test
    void onlyAllowlistedVerbsPass() {
        List<String> allow = List.of("say", "spawn");
        assertTrue(CommandAllowlist.allows(true, allow, "say hello"));
        assertTrue(CommandAllowlist.allows(true, allow, "/spawn"));
        assertFalse(CommandAllowlist.allows(true, allow, "op someone"));
        assertFalse(CommandAllowlist.allows(true, allow, "sayhi"));
    }

    @Test
    void blankCommandsAreRejected() {
        assertFalse(CommandAllowlist.allows(true, List.of("say"), "  "));
    }
}
