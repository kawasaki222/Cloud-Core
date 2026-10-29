package com.cloudcore.api.surface;

import com.cloudcore.api.SurfaceType;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side handle for one open surface. It deliberately contains no Bukkit types, so an
 * addon can implement {@link SurfaceProvider} against {@code cloudcore-api} alone.
 *
 * @param playerId   owner of the session
 * @param uiId       session id; must match the document id sent over the wire
 * @param surface    surface this session renders on
 * @param documentId id of the document the session displays
 * @param revision   document revision, non-negative
 * @param token      correlation token echoed in {@code UI_READY}
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
