package com.cloudcore.bukkit.slot;

import com.cloudcore.api.BindMode;
import com.cloudcore.api.RenderMode;
import com.cloudcore.api.SurfaceType;
import com.cloudcore.bukkit.CloudCorePlugin;
import com.cloudcore.bukkit.CloudInventoryHolder;
import com.cloudcore.protocol.Frame;
import com.cloudcore.protocol.FrameCodec;
import com.cloudcore.protocol.Fragmenter;
import com.cloudcore.protocol.Limits;
import com.cloudcore.protocol.Opcode;
import com.cloudcore.protocol.ProtocolException;
import com.cloudcore.protocol.ProtocolVersion;
import com.cloudcore.protocol.UiCodec;
import com.cloudcore.protocol.UiDefinition;
import com.cloudcore.protocol.UiOpen;
import com.cloudcore.server.surface.ContainerClickPolicy;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * A persistent, data-driven accessory page. It opens its own 9xN container, reuses the
 * CloudCore container surface and slot layout, and stores one YAML file per player under
 * {@code plugins/CloudCore/accessories/}.
 *
 * <p>Rules are enforced on the server in the click and drag handlers; the client only sees
 * the generated page. Closing the page flushes its current contents before the inventory is
 * discarded.
 */
public final class AccessorySlots {
    public static final String UI_ID = "cloudcore_accessories";

    private final Plugin plugin;
    private final SlotCatalog catalog;
    private final Path directory;

    public AccessorySlots(Plugin plugin) {
        this.plugin = plugin;
        this.catalog = SlotCatalog.load(plugin);
        this.directory = plugin.getDataFolder().toPath().resolve("accessories");
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            plugin.getLogger().warning("Cannot create accessories directory: "
                    + exception.getMessage());
        }
    }

    public SlotCatalog catalog() {
        return catalog;
    }

    public boolean owns(CloudInventoryHolder holder) {
        return holder != null && UI_ID.equals(holder.uiId());
    }

    /** Generates the page and opens the backing inventory after the definition is sent. */
    public void open(Player player) {
        if (catalog.slots().isEmpty()) {
            player.sendMessage(Component.text("Cloud Core: no accessory slots are configured."));
            return;
        }
        String json = documentJson();
        UiDefinition definition = new UiDefinition(UI_ID, 1, 0,
                json.getBytes(StandardCharsets.UTF_8));
        int token = java.util.concurrent.ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        UiOpen open = new UiOpen(UI_ID, 1, SurfaceType.CONTAINER, BindMode.NEXT_CONTAINER,
                token, RenderMode.SCREEN, 0, "accessories");
        send(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_OPEN,
                UiCodec.encodeOpen(open, Limits.S2C)));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            send(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION,
                    UiCodec.encodeDefinition(definition, Limits.S2C)));
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    openInventory(player);
                }
            }, 1L);
        }, 1L);
    }

    private void openInventory(Player player) {
        Map<Integer, SlotDefinition> guards = new LinkedHashMap<>();
        Set<Integer> declared = new LinkedHashSet<>();
        for (SlotDefinition slot : catalog.slots()) {
            declared.add(slot.position());
            guards.put(slot.position(), slot);
        }
        CloudInventoryHolder holder = new CloudInventoryHolder(player.getUniqueId(), UI_ID,
                ContainerClickPolicy.Interaction.MANAGED, declared, guards);
        Inventory inventory = Bukkit.createInventory(holder, catalog.rows() * 9,
                Component.text(catalog.title()));
        holder.attach(inventory);
        load(player, inventory);
        player.openInventory(inventory);
        plugin.getLogger().info("Opened accessory page for " + player.getName()
                + " slots=" + declared);
    }

    /** Called by the close listener; the inventory is still readable at that point. */
    public void save(Player player, Inventory inventory) {
        if (player == null || inventory == null) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (SlotDefinition slot : catalog.slots()) {
            ItemStack item = inventory.getItem(slot.position());
            if (item != null && !item.getType().isAir()) {
                yaml.set("items." + slot.id(), item);
            }
        }
        Path file = directory.resolve(player.getUniqueId() + ".yml");
        try {
            yaml.save(file.toFile());
        } catch (IOException exception) {
            plugin.getLogger().warning("Cannot save accessories for " + player.getName()
                    + ": " + exception.getMessage());
        }
    }

    private void load(Player player, Inventory inventory) {
        File file = directory.resolve(player.getUniqueId() + ".yml").toFile();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (SlotDefinition slot : catalog.slots()) {
            ItemStack item = yaml.getItemStack("items." + slot.id());
            if (item != null && !item.getType().isAir() && slot.accepts(player, item)) {
                inventory.setItem(slot.position(), item);
            }
        }
    }

    private String documentJson() {
        int rows = catalog.rows();
        int height = 166;
        StringBuilder json = new StringBuilder(2048);
        json.append("{\"schema\":1,\"id\":\"").append(UI_ID)
                .append("\",\"surface\":\"container\",")
                .append("\"title\":{\"runs\":[{\"text\":\"")
                .append(escape(catalog.title())).append("\"}]},")
                .append("\"size\":[176,").append(height).append("],")
                .append("\"container\":{\"rows\":").append(rows)
                .append(",\"interaction\":\"managed\",\"hide_player_inventory\":true},")
                .append("\"elements\":[")
                .append("{\"id\":\"accessory_bg\",\"type\":\"panel\",\"x\":0,\"y\":0,")
                .append("\"width\":\"fill\",\"height\":\"fill\",")
                .append("\"style\":{\"background\":\"#E0202838\"}},")
                .append("{\"id\":\"accessory_title\",\"type\":\"label\",\"x\":8,\"y\":8,")
                .append("\"text\":\"").append(escape(catalog.title()))
                .append("\",\"color\":\"#FFFFE0A0\"}");
        for (SlotDefinition slot : catalog.slots()) {
            int column = slot.position() % 9;
            int row = slot.position() / 9;
            int x = 8 + column * 18;
            int y = 26 + row * 18;
            json.append(",{\"id\":\"slot_art_").append(escape(slot.id()))
                    .append("\",\"type\":\"image\",\"x\":").append(x - 1)
                    .append(",\"y\":").append(y - 1)
                    .append(",\"width\":18,\"height\":18,")
                    .append("\"texture\":\"builtin:slot\",\"layers\":[")
                    .append("{\"texture\":\"builtin:slot_glow\",\"tint\":\"#40FFD060\"}")
                    .append("]}");
            json.append(",{\"id\":\"slot_grid_").append(escape(slot.id()))
                    .append("\",\"type\":\"inventory_grid\",\"x\":0,\"y\":0,")
                    .append("\"width\":\"fill\",\"height\":18,")
                    .append("\"slots\":[").append(slot.position()).append(',')
                    .append(slot.position()).append("],")
                    .append("\"origin\":[").append(x).append(',').append(y).append("],")
                    .append("\"cell\":[18,18],\"columns\":1}");
            json.append(",{\"id\":\"slot_label_").append(escape(slot.id()))
                    .append("\",\"type\":\"label\",\"x\":").append(x)
                    .append(",\"y\":").append(y + 19)
                    .append(",\"width\":18,\"height\":8,\"font_size\":6,\"text\":\"")
                    .append(escape(slot.label()))
                    .append("\",\"color\":\"#FFB0B8C8\"}");
        }
        json.append("]}");
        return json.toString();
    }

    private void send(Player player, Frame frame) {
        try {
            int fragmentId = Fragmenter.randomFragmentId(
                    java.util.concurrent.ThreadLocalRandom.current());
            for (Frame part : Fragmenter.fragment(FrameCodec.compressIfUseful(frame), Limits.S2C,
                    fragmentId)) {
                byte[] data = FrameCodec.encode(part, Limits.S2C);
                player.sendPluginMessage(plugin, CloudCorePlugin.CHANNEL, data);
            }
        } catch (ProtocolException exception) {
            plugin.getLogger().warning("Cannot send accessory page to " + player.getName()
                    + ": " + exception.getMessage());
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", "\\n");
    }
}
