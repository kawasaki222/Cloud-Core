package com.cloudcore.api.event;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A client event, published after decoding and before any action runs.
 *
 * <p>{@code actionId} is the server-side action table key the client echoed; the element's
 * event name ({@code click}, {@code change}, ...) is not on the wire. {@code payload} holds
 * plain Java values (String, Double, Boolean) and is immutable.
 */
public record ElementEvent(
        UUID playerId,
        String uiId,
        String elementId,
        int actionId,
        Map<String, Object> payload) {

    public ElementEvent {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(uiId, "uiId");
        Objects.requireNonNull(elementId, "elementId");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
