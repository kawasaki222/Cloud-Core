package com.cloudcore.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Stable addon-facing API. Implementations live in the server plugin; addons must not
 * depend on internal implementation classes.
 *
 * <p>All methods are safe to call from any thread; blocking work is scheduled to the
 * server main thread internally.
 */
public interface CloudCoreApi {

    OpenResult open(UUID playerId, String pageId, OpenOptions options);

    boolean close(UUID playerId, SurfaceType surface);

    Optional<String> activeDocument(UUID playerId, SurfaceType surface);

    void setState(UUID playerId, String uiId, String key, Object value);

    Optional<Object> getState(UUID playerId, String uiId, String key);

    void reload();
}
