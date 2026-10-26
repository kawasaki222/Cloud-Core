package com.cloudcore.bukkit;

import com.cloudcore.server.surface.ContainerClickPolicy;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marks a Bukkit inventory as owned by a Cloud container surface and carries everything the
 * click listener needs: the interaction mode and the slots the document actually declared.
 *
 * <p>Main thread only, like every Bukkit inventory.
 */
public final class CloudInventoryHolder implements InventoryHolder {
    private final UUID playerId;
    private final String uiId;
    private final ContainerClickPolicy.Interaction interaction;
    private final Set<Integer> declaredSlots;
    private Inventory inventory;

    public CloudInventoryHolder(UUID playerId, String uiId,
            ContainerClickPolicy.Interaction interaction, Set<Integer> declaredSlots) {
        this.playerId = playerId;
        this.uiId = uiId;
        this.interaction = interaction;
        this.declaredSlots = Collections.unmodifiableSet(new LinkedHashSet<>(declaredSlots));
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("Cloud inventory not attached yet");
        }
        return inventory;
    }

    void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    public UUID playerId() {
        return playerId;
    }

    public String uiId() {
        return uiId;
    }

    public ContainerClickPolicy.Interaction interaction() {
        return interaction;
    }

    public Set<Integer> declaredSlots() {
        return declaredSlots;
    }
}
