package com.cloudcore.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Published when a UI closes, either because the server sent {@code UI_CLOSE}
 * ({@code reason=server}) or because the client reported {@code UI_CLOSED}
 * ({@code reason=client}).
 */
public record SessionCloseEvent(UUID playerId, String uiId, String reason) {

    public SessionCloseEvent {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(uiId, "uiId");
        Objects.requireNonNull(reason, "reason");
    }
}
