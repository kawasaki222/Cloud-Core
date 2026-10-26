package com.cloudcore.server.surface;

import com.cloudcore.api.document.ContainerSpec;
import com.cloudcore.api.document.UiDocument;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Click policy for a Cloud container surface, mirroring {@code docs/04-server.md} §7.1.
 *
 * <p>The client never decides whether a click is legal: the server cancels disallowed clicks
 * before any plugin sees them. Unknown interaction names fail closed.
 */
public final class ContainerClickPolicy {
    public enum Interaction {
        MANAGED,
        READONLY,
        PASSTHROUGH;

        public static Interaction parse(String value, Interaction fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
    }

    private ContainerClickPolicy() {
    }

    /**
     * @param interaction        policy of the container document
     * @param topInventory       whether the clicked slot belongs to the Cloud inventory
     * @param slot               raw slot index inside that inventory (ignored for the bottom one)
     * @param declaredSlots      slots declared by {@code inventory_grid} elements
     * @param allowPlayerInventory whether readonly containers let the player use their own slots
     * @return {@code true} when the click may proceed
     */
    public static boolean allow(Interaction interaction,
            boolean topInventory,
            int slot,
            Set<Integer> declaredSlots,
            boolean allowPlayerInventory) {
        Objects.requireNonNull(interaction, "interaction");
        if (!topInventory) {
            return interaction != Interaction.READONLY || allowPlayerInventory;
        }
        return switch (interaction) {
            case PASSTHROUGH -> true;
            case READONLY -> false;
            case MANAGED -> declaredSlots.contains(slot);
        };
    }

    /** Fails closed: an unparsable policy denies every click. */
    public static boolean allow(String interactionName,
            boolean topInventory,
            int slot,
            Set<Integer> declaredSlots,
            boolean allowPlayerInventory) {
        Interaction interaction = Interaction.parse(interactionName, Interaction.MANAGED);
        return interaction != null
                && allow(interaction, topInventory, slot, declaredSlots, allowPlayerInventory);
    }

    public static Interaction interactionOf(UiDocument document) {
        ContainerSpec container = document.container();
        if (container == null) {
            return Interaction.MANAGED;
        }
        Interaction interaction = Interaction.parse(container.interaction(), Interaction.MANAGED);
        return interaction == null ? Interaction.READONLY : interaction;
    }

    /**
     * Reads the slot ranges declared by {@code inventory_grid} elements. Ranges are
     * {@code slots: [first, last]} and every slot in the range is managed.
     */
    public static Set<Integer> declaredSlots(UiDocument document) {
        Objects.requireNonNull(document, "document");
        Set<Integer> slots = new LinkedHashSet<>();
        for (var element : document.elements()) {
            if (!"inventory_grid".equals(element.type())) {
                continue;
            }
            Object range = element.attributes().get("slots");
            if (range instanceof List<?> pair && pair.size() == 2) {
                int first = toInt(pair.get(0));
                int last = toInt(pair.get(1));
                for (int slot = Math.max(0, Math.min(first, last));
                        slot <= Math.max(first, last); slot++) {
                    slots.add(slot);
                }
            }
        }
        return Collections.unmodifiableSet(slots);
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException exception) {
                return -1;
            }
        }
        return -1;
    }
}
