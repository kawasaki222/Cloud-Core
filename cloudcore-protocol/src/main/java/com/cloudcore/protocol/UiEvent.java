package com.cloudcore.protocol;

import java.util.Map;
import java.util.Objects;

/**
 * Client -> server {@code EVENT}. {@code actionId} comes from the document's
 * {@code events: {click: actionId}} map, so reordering YAML never changes it.
 *
 * @param requestId monotonically increasing per connection; the server may cache results
 * @param payload   event-specific values (see docs/02-protocol.md §7)
 */
public record UiEvent(int requestId, String uiId, String elementId, int actionId,
        Map<String, WireValue> payload) {

    public UiEvent {
        Objects.requireNonNull(uiId, "uiId");
        Objects.requireNonNull(elementId, "elementId");
        Objects.requireNonNull(payload, "payload");
        if (requestId < 0) {
            throw new IllegalArgumentException("requestId must be non-negative");
        }
        if (actionId < 0 || actionId > 0xFFFF) {
            throw new IllegalArgumentException("actionId out of range: " + actionId);
        }
        payload = Map.copyOf(payload);
    }
}
