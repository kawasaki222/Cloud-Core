package com.cloudcore.api.document;

import java.util.Objects;

/**
 * Container-only document fields.
 *
 * @param rows                 number of 9-slot rows in the Cloud container
 * @param interaction          server-side click policy: managed, readonly or passthrough
 * @param hidePlayerInventory  hide the player-inventory slots under a purpose-built page
 * @param allowPlayerInventory whether readonly containers let the player use their own slots
 */
public record ContainerSpec(int rows, String interaction, boolean hidePlayerInventory,
        boolean allowPlayerInventory) {

    public ContainerSpec(int rows, String interaction) {
        this(rows, interaction, false, false);
    }

    public ContainerSpec(int rows, String interaction, boolean hidePlayerInventory) {
        this(rows, interaction, hidePlayerInventory, false);
    }

    public ContainerSpec {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("rows must be between 1 and 6");
        }
        Objects.requireNonNull(interaction, "interaction");
    }
}
