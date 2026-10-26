package com.cloudcore.server.surface;

import com.cloudcore.api.OpenOptions;
import com.cloudcore.api.SurfaceType;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side surface implementation. Bukkit-facing providers live in the plugin module;
 * this interface stays pure Java so it can be tested and reused.
 */
public interface SurfaceProvider {

    SurfaceType type();

    boolean available();

    Optional<SurfaceSession> open(UUID playerId, String documentId, OpenOptions options);

    default void apply(SurfaceSession session) {
    }

    void close(UUID playerId, String uiId);
}
