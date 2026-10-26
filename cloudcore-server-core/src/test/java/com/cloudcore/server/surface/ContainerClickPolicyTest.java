package com.cloudcore.server.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.ContainerSpec;
import com.cloudcore.api.document.TextRuns;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContainerClickPolicyTest {
    private static final Set<Integer> DECLARED = Set.of(0, 1, 2, 9, 10);

    @Test
    void managedAllowsDeclaredSlotsAndPlayerInventory() {
        assertTrue(ContainerClickPolicy.allow(
                ContainerClickPolicy.Interaction.MANAGED, true, 10, DECLARED, false));
        assertFalse(ContainerClickPolicy.allow(
                ContainerClickPolicy.Interaction.MANAGED, true, 11, DECLARED, false));
        assertTrue(ContainerClickPolicy.allow(
                ContainerClickPolicy.Interaction.MANAGED, false, 40, DECLARED, false));
    }

    @Test
    void readonlyCancelsEverythingUntilExplicitlyAllowed() {
        assertFalse(ContainerClickPolicy.allow(
                ContainerClickPolicy.Interaction.READONLY, true, 0, DECLARED, false));
        assertFalse(ContainerClickPolicy.allow(
                ContainerClickPolicy.Interaction.READONLY, false, 40, DECLARED, false));
        assertTrue(ContainerClickPolicy.allow(
                ContainerClickPolicy.Interaction.READONLY, false, 40, DECLARED, true));
    }

    @Test
    void passthroughNeverCancels() {
        assertTrue(ContainerClickPolicy.allow(
                ContainerClickPolicy.Interaction.PASSTHROUGH, true, 53, DECLARED, false));
        assertTrue(ContainerClickPolicy.allow(
                ContainerClickPolicy.Interaction.PASSTHROUGH, false, 40, DECLARED, false));
    }

    @Test
    void unknownInteractionFailsClosed() {
        assertNull(ContainerClickPolicy.Interaction.parse("managed?", ContainerClickPolicy.Interaction.MANAGED));
        assertFalse(ContainerClickPolicy.allow("skynet", true, 0, DECLARED, false));
        assertFalse(ContainerClickPolicy.allow("skynet", false, 40, DECLARED, true));
    }

    @Test
    void missingInteractionDefaultsToManaged() {
        assertEquals(ContainerClickPolicy.Interaction.MANAGED,
                ContainerClickPolicy.Interaction.parse(null, ContainerClickPolicy.Interaction.MANAGED));
        assertEquals(ContainerClickPolicy.Interaction.PASSTHROUGH,
                ContainerClickPolicy.Interaction.parse(" Passthrough ", ContainerClickPolicy.Interaction.MANAGED));
    }

    @Test
    void declaredSlotsComeFromInventoryGridElements() {
        UiDocument document = new UiDocument(1, "grid", SurfaceType.CONTAINER,
                TextRuns.plain("grid"), "default", 176, 166, 1.0f,
                new ContainerSpec(6, "managed"), Map.of(), Map.of(),
                List.of(
                        new UiElement("g1", "inventory_grid", 0, 0, "162", "54", true, true, 0,
                                Map.of(), List.of(), Map.of("slots", List.of(0, 8))),
                        new UiElement("g2", "inventory_grid", 0, 0, "162", "54", true, true, 0,
                                Map.of(), List.of(), Map.of("slots", List.of(9, 10))),
                        new UiElement("label", "label", 0, 0, "10", "10", true, true, 0,
                                Map.of(), List.of(), Map.of())));

        Set<Integer> slots = ContainerClickPolicy.declaredSlots(document);
        assertEquals(11, slots.size(), slots.toString());
        assertTrue(slots.contains(0));
        assertTrue(slots.contains(8));
        assertTrue(slots.contains(10));
        assertFalse(slots.contains(11));
    }

    @Test
    void invalidInteractionInDocumentDeniesClicks() {
        UiDocument document = new UiDocument(1, "bad", SurfaceType.CONTAINER,
                TextRuns.plain("bad"), "default", 176, 166, 1.0f,
                new ContainerSpec(6, "managed?"), Map.of(), Map.of(), List.of());
        assertEquals(ContainerClickPolicy.Interaction.READONLY,
                ContainerClickPolicy.interactionOf(document));
    }
}
