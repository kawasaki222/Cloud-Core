package com.cloudcore.bukkit.slot;

import java.util.List;
import java.util.Locale;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * One custom slot declared in {@code slots.yml}. A definition is deliberately data-only:
 * the same record powers the generated page, the click guard and the persisted store.
 *
 * <p>The acceptance rules are opt-in. With no {@code accepts} block every item fits;
 * with {@code lore-contains} the item's display name or any lore line must contain at least
 * one of the configured strings. Materials and permissions are additional AND constraints.
 */
public record SlotDefinition(String id, int position, String label,
        List<String> loreContains, List<String> materials, String permission) {

    public SlotDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("slot id is required");
        }
        if (position < 0 || position > 53) {
            throw new IllegalArgumentException("slot position must be 0..53");
        }
        label = label == null || label.isBlank() ? id : label;
        loreContains = loreContains == null ? List.of() : List.copyOf(loreContains);
        materials = materials == null ? List.of() : List.copyOf(materials);
        permission = permission == null ? "" : permission;
    }

    public boolean accepts(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (!permission.isBlank() && (player == null || !player.hasPermission(permission))) {
            return false;
        }
        String material = stack.getType().getKey().getKey().toUpperCase(Locale.ROOT);
        if (!materials.isEmpty() && materials.stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .noneMatch(material::equals)) {
            return false;
        }
        if (loreContains.isEmpty()) {
            return true;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        String display = meta.hasDisplayName() ? plain(meta.displayName()) : "";
        List<String> lore = meta.lore() == null ? List.of()
                : meta.lore().stream().map(SlotDefinition::plain).toList();
        for (String needle : loreContains) {
            String wanted = needle.toLowerCase(Locale.ROOT);
            if (display.toLowerCase(Locale.ROOT).contains(wanted)) {
                return true;
            }
            for (String line : lore) {
                if (line.toLowerCase(Locale.ROOT).contains(wanted)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(component);
    }
}
