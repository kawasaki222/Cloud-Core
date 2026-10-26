package com.cloudcore.api.document;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One already-expanded element in a downloaded UI document.
 *
 * <p>{@code width} and {@code height} accept pixels as a decimal string, percentages such
 * as {@code "60%"}, or {@code "fill"}. Type-specific fields live in {@code attributes} so
 * new element types do not change this record.
 */
public record UiElement(
        String id,
        String type,
        int x,
        int y,
        String width,
        String height,
        boolean visible,
        boolean enabled,
        int z,
        Map<String, Integer> events,
        List<UiElement> children,
        Map<String, Object> attributes) {

    public UiElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        events = Map.copyOf(Objects.requireNonNullElse(events, Map.of()));
        children = List.copyOf(Objects.requireNonNullElse(children, List.of()));
        attributes = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNullElse(attributes, Map.of())));
    }
}
