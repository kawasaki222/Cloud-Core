package com.cloudcore.bukkit.item;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.plugin.Plugin;

/**
 * Binds a 1.21.11 data-driven {@code equipment} asset to an equippable item.
 *
 * <p>The asset itself lives in a resource pack as
 * {@code assets/<namespace>/equipment/<path>.json}. Vanilla then renders its
 * {@code humanoid}/{@code humanoid_leggings} layers, including multiple textures and
 * dyeable layers, without a client mixin. CloudCore's job is only to set the
 * {@code equippable.model} component on the stack.
 */
public final class EquipmentAssets {
    private EquipmentAssets() {
    }

    /**
     * @param stack   an equippable item such as a chestplate or helmet
     * @param assetId namespaced equipment asset id, for example {@code cloudcore:valkyrie}
     * @return true when the component was written; false for non-equippable items or invalid ids
     */
    public static boolean apply(ItemStack stack, String assetId) {
        return apply(stack, assetId, null);
    }

    /**
     * @param slot optional slot override; {@code null} keeps the item's existing slot
     */
    public static boolean apply(ItemStack stack, String assetId, EquipmentSlot slot) {
        if (stack == null || stack.getType().isAir() || assetId == null || assetId.isBlank()) {
            return false;
        }
        NamespacedKey model = NamespacedKey.fromString(assetId.trim().toLowerCase(java.util.Locale.ROOT));
        if (model == null) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        // Vanilla armour derives equippable behaviour from its material, so
        // hasEquippable() can be false even though getEquippable() is usable.
        EquippableComponent equippable = meta.getEquippable();
        if (equippable == null) {
            return false;
        }
        equippable.setModel(model);
        EquipmentSlot effectiveSlot = slot == null ? inferSlot(stack) : slot;
        if (effectiveSlot != null) {
            equippable.setSlot(effectiveSlot);
        }
        meta.setEquippable(equippable);
        stack.setItemMeta(meta);
        return true;
    }

    /** Vanilla armour materials keep their natural slot when the caller does not override it. */
    private static EquipmentSlot inferSlot(ItemStack stack) {
        String type = stack.getType().name();
        if (type.endsWith("_HELMET") || type.equals("TURTLE_HELMET")
                || type.equals("CARVED_PUMPKIN")) {
            return EquipmentSlot.HEAD;
        }
        if (type.endsWith("_CHESTPLATE") || type.equals("ELYTRA")) {
            return EquipmentSlot.CHEST;
        }
        if (type.endsWith("_LEGGINGS")) {
            return EquipmentSlot.LEGS;
        }
        if (type.endsWith("_BOOTS")) {
            return EquipmentSlot.FEET;
        }
        return null;
    }

    /**
     * Applies an asset to the item in the player's main hand. Intended for commands and
     * addon/testing code; production item builders can call {@link #apply(ItemStack, String)}.
     */
    public static boolean applyToHand(Player player, String assetId, Plugin plugin) {
        if (player == null || plugin == null) {
            return false;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!apply(hand, assetId)) {
            return false;
        }
        player.getInventory().setItemInMainHand(hand);
        return true;
    }
}
