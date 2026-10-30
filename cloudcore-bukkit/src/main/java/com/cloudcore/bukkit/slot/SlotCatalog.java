package com.cloudcore.bukkit.slot;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Reads the operator-owned slot catalogue. CloudCore ships a small default so the feature
 * can be opened immediately; after the first run the file belongs to the server owner.
 *
 * <p>Format (own schema, independent of any other UI plugin):
 * <pre>
 * title: "饰品"
 * rows: 3
 * slots:
 *   necklace:
 *     position: 10
 *     label: "项链"
 *     accepts:
 *       lore-contains: ["项链"]
 *       materials: ["DIAMOND"]
 *     permission: "cloudcore.slot.necklace"
 * </pre>
 */
public final class SlotCatalog {
    private static final int MAX_ROWS = 6;

    private final String title;
    private final int rows;
    private final List<SlotDefinition> slots;
    private final Map<Integer, SlotDefinition> byPosition;

    private SlotCatalog(String title, int rows, List<SlotDefinition> slots) {
        this.title = title;
        this.rows = rows;
        this.slots = List.copyOf(slots);
        Map<Integer, SlotDefinition> positions = new LinkedHashMap<>();
        for (SlotDefinition slot : slots) {
            positions.putIfAbsent(slot.position(), slot);
        }
        this.byPosition = Collections.unmodifiableMap(positions);
    }

    public static SlotCatalog load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "slots.yml");
        if (!file.isFile()) {
            plugin.saveResource("slots.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String title = yaml.getString("title", "饰品");
        int rows = Math.max(1, Math.min(MAX_ROWS, yaml.getInt("rows", 3)));
        List<SlotDefinition> slots = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection("slots");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(id);
                if (entry == null) {
                    continue;
                }
                int position = entry.getInt("position", -1);
                if (position < 0 || position >= rows * 9) {
                    plugin.getLogger().warning("slots.yml: skipping " + id
                            + " because position " + position + " is outside 0.." + (rows * 9 - 1));
                    continue;
                }
                ConfigurationSection accepts = entry.getConfigurationSection("accepts");
                slots.add(new SlotDefinition(
                        id,
                        position,
                        entry.getString("label", id),
                        accepts == null ? List.of() : accepts.getStringList("lore-contains"),
                        accepts == null ? List.of() : accepts.getStringList("materials"),
                        entry.getString("permission", "")));
            }
        }
        if (slots.isEmpty()) {
            plugin.getLogger().warning("slots.yml declares no usable slots");
        }
        return new SlotCatalog(title, rows, slots);
    }

    public String title() {
        return title;
    }

    public int rows() {
        return rows;
    }

    public List<SlotDefinition> slots() {
        return slots;
    }

    public SlotDefinition at(int position) {
        return byPosition.get(position);
    }
}
