package com.cloudcore.server.surface;

import com.cloudcore.api.SurfaceType;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side handle for one open surface. It deliberately contains no Bukkit types.
 */
public record SurfaceSession(
        UUID playerId,
        String uiId,
        SurfaceType surface,
        String documentId,
        int revision,
        int token) {

    public SurfaceSession {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(uiId, "uiId");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(documentId, "documentId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
    }
}
