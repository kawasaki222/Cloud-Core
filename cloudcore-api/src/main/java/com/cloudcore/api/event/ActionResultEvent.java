package com.cloudcore.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Published after the actions bound to an element event finished.
 *
 * <p>{@code status} uses the protocol {@code EVENT_RESULT} codes: {@code 0} ok, {@code 1}
 * unknown element, {@code 2} rejected, {@code 3} rate limited.
 */
public record ActionResultEvent(
        UUID playerId,
        String uiId,
        String elementId,
        int actionId,
        int status) {

    public ActionResultEvent {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(uiId, "uiId");
        Objects.requireNonNull(elementId, "elementId");
    }
}
