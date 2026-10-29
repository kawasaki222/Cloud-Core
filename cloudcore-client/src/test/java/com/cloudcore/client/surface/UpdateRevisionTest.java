package com.cloudcore.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UpdateRevisionTest {

    @Test
    void acceptsExactlyTheNextRevision() {
        assertEquals(UpdateRevision.Decision.APPLY, UpdateRevision.decide(4, 5));
    }

    @Test
    void rejectsOldRevisions() {
        assertEquals(UpdateRevision.Decision.STALE, UpdateRevision.decide(4, 4));
        assertEquals(UpdateRevision.Decision.STALE, UpdateRevision.decide(4, 3));
    }

    @Test
    void rejectsGaps() {
        assertEquals(UpdateRevision.Decision.GAP, UpdateRevision.decide(4, 6));
    }
}
