package com.cloudcore.api.document;

import java.util.Objects;

/**
 * A font declaration in a downloaded UI document.
 */
public record FontSpec(
        String provider,
        String source,
        String id,
        String fallback,
        int lineGap,
        boolean shadow) {

    public FontSpec {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(id, "id");
        if (lineGap < 0) {
            throw new IllegalArgumentException("lineGap must be non-negative");
        }
    }
}
