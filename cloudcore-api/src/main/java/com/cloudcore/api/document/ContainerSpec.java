package com.cloudcore.api.document;

import java.util.Objects;

/**
 * Container-only document fields.
 */
public record ContainerSpec(int rows, String interaction) {

    public ContainerSpec {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("rows must be between 1 and 6");
        }
        Objects.requireNonNull(interaction, "interaction");
    }
}
