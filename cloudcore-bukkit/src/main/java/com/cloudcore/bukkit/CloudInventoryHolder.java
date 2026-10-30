package com.cloudcore.bukkit;

import com.cloudcore.bukkit.slot.SlotDefinition;
import com.cloudcore.server.surface.ContainerClickPolicy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Marks a Bukkit inventory as owned by a Cloud container surface and carries everything the
 * click listener needs: the interaction mode, the slots the document declared, and optional
 * per-slot placement guards.
 *
 * <p>Main thread only, like every Bukkit inventory.
 */
public final class CloudInventoryHolder implements InventoryHolder {
    private final UUID playerId;
    private final String uiId;
    private final ContainerClickPolicy.Interaction interaction;
    private final Set<Integer> declaredSlots;
    private final Map<Integer, SlotDefinition> guards;
    private final boolean allowPlayerInventory;
    private Inventory inventory;

    public CloudInventoryHolder(UUID playerId, String uiId,
            ContainerClickPolicy.Interaction interaction, Set<Integer> declaredSlots) {
        this(playerId, uiId, interaction, declaredSlots, Map.of());
    }

    public CloudInventoryHolder(UUID playerId, String uiId,
            ContainerClickPolicy.Interaction interaction, Set<Integer> declaredSlots,
            Map<Integer, SlotDefinition> guards) {
        this(playerId, uiId, interaction, declaredSlots, guards, false);
    }

    public CloudInventoryHolder(UUID playerId, String uiId,
            ContainerClickPolicy.Interaction interaction, Set<Integer> declaredSlots,
            Map<Integer, SlotDefinition> guards, boolean allowPlayerInventory) {
        this.playerId = playerId;
        this.uiId = uiId;
        this.interaction = interaction;
        this.declaredSlots = Collections.unmodifiableSet(new LinkedHashSet<>(declaredSlots));
        this.guards = Collections.unmodifiableMap(new LinkedHashMap<>(guards));
        this.allowPlayerInventory = allowPlayerInventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("Cloud inventory not attached yet");
        }
        return inventory;
    }

    public void attach(Inventory inventory) {
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

    public Map<Integer, SlotDefinition> guards() {
        return guards;
    }

    public boolean allowPlayerInventory() {
        return allowPlayerInventory;
    }

    /** True when the slot accepts the given item for this player; unguarded slots are open. */
    public boolean canPlace(Player player, int slot, ItemStack stack) {
        SlotDefinition guard = guards.get(slot);
        return guard == null || guard.accepts(player, stack);
    }

    /** True when the slot has any placement rule, so callers can avoid needless checks. */
    public boolean guarded(int slot) {
        return guards.containsKey(slot);
    }
}
