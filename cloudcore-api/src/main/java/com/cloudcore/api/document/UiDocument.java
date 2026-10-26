package com.cloudcore.api.document;

import com.cloudcore.api.SurfaceType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The immutable document delivered by {@code UI_DEFINITION}.
 *
 * <p>It contains no state, actions, permissions, includes, repeat templates or raw URLs;
 * those exist only on the server.
 */
public record UiDocument(
        int schema,
        String id,
        SurfaceType surface,
        TextRuns title,
        String theme,
        int width,
        int height,
        float scale,
        ContainerSpec container,
        Map<String, Object> surfaceOptions,
        Map<String, FontSpec> fonts,
        List<UiElement> elements) {

    public static final int CURRENT_SCHEMA = 1;

    public UiDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(title, "title");
        surfaceOptions = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNullElse(surfaceOptions, Map.of())));
        fonts = Map.copyOf(Objects.requireNonNullElse(fonts, Map.of()));
        elements = List.copyOf(Objects.requireNonNullElse(elements, List.of()));
    }
}
