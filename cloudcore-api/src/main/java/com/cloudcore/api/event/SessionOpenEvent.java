package com.cloudcore.api.event;

import com.cloudcore.api.SurfaceType;
import java.util.Objects;
import java.util.UUID;

/**
 * Published after {@code UI_OPEN} and the document were queued for a player.
 *
 * <p>Listeners run on the server main thread and must not mutate the session; use the API for
 * that. Exceptions are caught by the dispatcher and logged, never propagated to the UI path.
 */
public record SessionOpenEvent(
        UUID playerId,
        String uiId,
        SurfaceType surface,
        String documentId,
        int revision) {

    public SessionOpenEvent {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(uiId, "uiId");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(documentId, "documentId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
    }
}
