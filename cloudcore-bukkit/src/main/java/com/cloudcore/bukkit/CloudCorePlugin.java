package com.cloudcore.bukkit;

import com.cloudcore.api.BindMode;
import com.cloudcore.api.RenderMode;
import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiDocumentJson;
import com.cloudcore.api.page.PageLoadException;
import com.cloudcore.protocol.Diagnostic;
import com.cloudcore.protocol.DiagnosticCodec;
import com.cloudcore.protocol.DiagnosticSeverity;
import com.cloudcore.protocol.FeatureFlags;
import com.cloudcore.protocol.Frame;
import com.cloudcore.protocol.FrameCodec;
import com.cloudcore.protocol.FrameFlags;
import com.cloudcore.protocol.FragmentAssembler;
import com.cloudcore.protocol.Fragmenter;
import com.cloudcore.protocol.HandshakeCodec;
import com.cloudcore.protocol.Hello;
import com.cloudcore.protocol.HelloAck;
import com.cloudcore.protocol.Limits;
import com.cloudcore.protocol.Opcode;
import com.cloudcore.protocol.Ping;
import com.cloudcore.protocol.Pong;
import com.cloudcore.protocol.ProtocolException;
import com.cloudcore.protocol.ProtocolVersion;
import com.cloudcore.protocol.UiClose;
import com.cloudcore.protocol.UiClosed;
import com.cloudcore.protocol.UiCodec;
import com.cloudcore.protocol.UiDefinition;
import com.cloudcore.protocol.UiOpen;
import com.cloudcore.protocol.UiReady;
import com.cloudcore.server.surface.ContainerClickPolicy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

public final class CloudCorePlugin extends JavaPlugin
        implements PluginMessageListener, CommandExecutor, Listener {

    public static final String CHANNEL = "cloudcore:main";
    private static final int SERVER_FEATURES = FeatureFlags.NONE;
    private static final String PING_PERMISSION = "cloudcore.admin";

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Override
    public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("cloudcore") != null) {
            getCommand("cloudcore").setExecutor(this);
        } else {
            getLogger().warning("Command 'cloudcore' is missing from plugin.yml");
        }
        getLogger().info("Cloud Core transport online on " + CHANNEL
                + " (protocol " + ProtocolVersion.MAJOR + "." + ProtocolVersion.MINOR + ")");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
        sessions.clear();
        getLogger().info("Cloud Core transport offline");
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        try {
            Frame frame = FrameCodec.decode(message, Limits.C2S);
            if ((frame.flags() & FrameFlags.FRAGMENT) != 0) {
                Optional<Frame> complete = session.assembler.accept(frame);
                if (complete.isEmpty()) {
                    return;
                }
                frame = complete.get();
            }
            dispatch(player, session, frame);
        } catch (ProtocolException exception) {
            session.assembler.clear();
            getLogger().warning("Dropped malformed frame from " + player.getUniqueId()
                    + ": " + exception.getMessage());
        } catch (RuntimeException exception) {
            session.assembler.clear();
            getLogger().log(Level.SEVERE,
                    "Unexpected frame failure for " + player.getUniqueId(), exception);
        }
    }

    private void dispatch(Player player, Session session, Frame frame) {
        switch (frame.opcode()) {
            case Opcode.HELLO -> handleHello(player, session, frame);
            case Opcode.PONG -> handlePong(player, session, frame);
            case Opcode.UI_READY -> handleUiReady(player, frame);
            case Opcode.UI_CLOSED -> handleUiClosed(player, frame);
            default -> getLogger().info("Unhandled C2S opcode=0x"
                    + Integer.toHexString(frame.opcode()) + " bytes=" + frame.bodyLength()
                    + " player=" + player.getUniqueId());
        }
    }

    private void handleHello(Player player, Session session, Frame frame) {
        Hello hello = HandshakeCodec.decodeHello(frame.body(), Limits.C2S);
        session.hello = hello;
        boolean compatible = ProtocolVersion.isCompatible(hello.protoMajor());
        getLogger().info("HELLO from " + player.getName()
                + " protocol=" + hello.protoMajor() + "." + hello.protoMinor()
                + " mod=" + hello.modVersion()
                + " features=0x" + Integer.toHexString(hello.features())
                + " locale=" + hello.locale()
                + " guiScale=" + hello.guiScale()
                + " scaled=" + hello.scaledWidth() + "x" + hello.scaledHeight()
                + (compatible ? "" : " (incompatible major)"));
        // The client's JOIN callback sends HELLO while its play handler is still being
        // installed. Answer one tick later so the client has a live receiver for S2C.
        getServer().getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                sendHelloAck(player);
            }
        });
    }

    private void handleUiClosed(Player player, Frame frame) {
        UiClosed closed = UiCodec.decodeClosed(frame.body(), Limits.C2S);
        getLogger().info("UI_CLOSED from " + player.getName()
                + " ui=" + closed.uiId() + " reason=" + closed.reason());
    }

    private void handleUiReady(Player player, Frame frame) {
        UiReady ready = UiCodec.decodeReady(frame.body(), Limits.C2S);
        if (ready.state() == 0) {
            getLogger().info("UI_READY from " + player.getName() + " ui=" + ready.uiId()
                    + " surface=" + ready.surface());
        } else {
            getLogger().warning("UI_READY failure from " + player.getName()
                    + " ui=" + ready.uiId() + " surface=" + ready.surface()
                    + " state=" + ready.state() + " detail=" + ready.detail());
        }
    }

    private void handlePong(Player player, Session session, Frame frame) {
        Pong pong = HandshakeCodec.decodePong(frame.body());
        if (!session.pingPending || pong.nonce() != session.pingNonce) {
            getLogger().info("Unexpected PONG nonce=0x" + Integer.toHexString(pong.nonce())
                    + " from " + player.getName());
            return;
        }
        session.pingPending = false;
        long rttMillis = Math.max(0L, (System.nanoTime() - session.pingSentNanos) / 1_000_000L);
        player.sendMessage(Component.text("Cloud Core RTT: " + rttMillis + " ms"));
        getLogger().info("PONG from " + player.getName() + " rtt=" + rttMillis + "ms");
    }

    private void sendHelloAck(Player player) {
        HelloAck ack = new HelloAck(
                ProtocolVersion.MAJOR,
                ProtocolVersion.MINOR,
                SERVER_FEATURES,
                getServer().getName(),
                Limits.S2C.maxFrameBytes(),
                HandshakeCodec.COMPRESSION_THRESHOLD,
                "Cloud Core " + getPluginMeta().getVersion());
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.HELLO_ACK,
                HandshakeCodec.encodeHelloAck(ack, Limits.S2C)));
        getLogger().info("Sent HELLO_ACK to " + player.getName()
                + " protocol=" + ack.protoMajor() + "." + ack.protoMinor()
                + " acceptedFeatures=0x" + Integer.toHexString(ack.acceptedFeatures()));
    }

    private void sendPing(Player player, Session session) {
        int nonce;
        do {
            nonce = random.nextInt();
        } while (nonce == 0);
        session.pingPending = true;
        session.pingNonce = nonce;
        session.pingSentNanos = System.nanoTime();
        Ping ping = new Ping(nonce, System.currentTimeMillis());
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.PING,
                HandshakeCodec.encodePing(ping)));
    }

    private void sendFrame(Player player, Frame frame) {
        try {
            int fragmentId = Fragmenter.randomFragmentId(random);
            for (Frame part : Fragmenter.fragment(
                    FrameCodec.compressIfUseful(frame), Limits.S2C, fragmentId)) {
                byte[] data = FrameCodec.encode(part, Limits.S2C);
                player.sendPluginMessage(this, CHANNEL, data);
                if (getConfig().getBoolean("debug", false)) {
                    getLogger().info("S2C opcode=0x" + Integer.toHexString(part.opcode())
                            + " bytes=" + data.length + " fragment="
                            + ((part.flags() & FrameFlags.FRAGMENT) != 0)
                            + " player=" + player.getUniqueId());
                }
            }
        } catch (ProtocolException exception) {
            getLogger().warning("Failed to encode opcode 0x" + Integer.toHexString(frame.opcode())
                    + " for " + player.getName() + ": " + exception.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PING_PERMISSION)) {
            sender.sendMessage(Component.text("No permission."));
            return true;
        }
        if (args.length == 2 && isKnownAction(args[0])) {
            Player target = getServer().getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found: " + args[1]));
                return true;
            }
            if (args[0].equalsIgnoreCase("ping")) {
                Session session = sessions.computeIfAbsent(target.getUniqueId(),
                        ignored -> new Session());
                sendPing(target, session);
            } else if (args[0].equalsIgnoreCase("probe")) {
                sendProbe(target);
            } else if (args[0].equalsIgnoreCase("boss")) {
                sendBoss(target);
            } else if (args[0].equalsIgnoreCase("close")) {
                sendClose(target);
            } else if (args[0].equalsIgnoreCase("diagnostic")) {
                sendDiagnostic(target);
            } else if (args[0].equalsIgnoreCase("demo-hud")) {
                sendHudOpen(target);
            } else if (args[0].equalsIgnoreCase("demo-tooltip")) {
                sendTooltipOpen(target);
            } else if (args[0].equalsIgnoreCase("demo-container")) {
                sendDemoContainer(target);
            } else if (args[0].equalsIgnoreCase("demo-readonly")) {
                openDemoChest(target, ContainerClickPolicy.Interaction.READONLY);
            } else if (args[0].equalsIgnoreCase("demo-inventory")) {
                sendDemoInventory(target);
            } else if (args[0].equalsIgnoreCase("demo-assets")) {
                sendDemoAssets(target);
            } else {
                if (args[0].equalsIgnoreCase("demo-huge")) {
                    sendDemoHuge(target);
                } else {
                    sendDemoOpen(target, args[0].equalsIgnoreCase("demo-big"));
                }
            }
            sender.sendMessage(Component.text("Cloud Core " + args[0] + " sent to "
                    + target.getName()));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(
                    "Usage: /cloudcore <ping|demo|demo-big|demo-huge|probe> <player>"));
            return true;
        }
        if (args.length == 1 && (args[0].equalsIgnoreCase("demo")
                || args[0].equalsIgnoreCase("demo-big"))) {
            sendDemoOpen(player, args[0].equalsIgnoreCase("demo-big"));
            sender.sendMessage(Component.text("Cloud Core demo page sent."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("demo-huge")) {
            sendDemoHuge(player);
            sender.sendMessage(Component.text("Cloud Core huge demo page sent."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("probe")) {
            sendProbe(player);
            sender.sendMessage(Component.text("Cloud Core probe inventory opened."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("boss")) {
            sendBoss(player);
            sender.sendMessage(Component.text("Cloud Core probe boss bar sent."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("close")) {
            sendClose(player);
            sender.sendMessage(Component.text("Cloud Core probe close sent."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("diagnostic")) {
            sendDiagnostic(player);
            sender.sendMessage(Component.text("Cloud Core diagnostic probe sent."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("demo-hud")) {
            sendHudOpen(player);
            sender.sendMessage(Component.text("Cloud Core HUD demo sent."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("demo-tooltip")) {
            sendTooltipOpen(player);
            sender.sendMessage(Component.text("Cloud Core tooltip demo sent."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("demo-container")) {
            sendDemoContainer(player);
            sender.sendMessage(Component.text("Cloud Core container demo sent."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("demo-readonly")) {
            openDemoChest(player, ContainerClickPolicy.Interaction.READONLY);
            sender.sendMessage(Component.text("Cloud Core readonly container demo sent."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("demo-inventory")) {
            sendDemoInventory(player);
            sender.sendMessage(Component.text("Cloud Core inventory demo sent."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("demo-assets")) {
            sendDemoAssets(player);
            sender.sendMessage(Component.text("Cloud Core asset demo sent."));
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("ping")) {
            sender.sendMessage(Component.text(
                    "Usage: /" + label
                            + " <ping|demo|demo-big|demo-hud|demo-tooltip|demo-container"
                            + "|demo-readonly|demo-inventory|probe|boss|close|diagnostic>"));
            return true;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        sendPing(player, session);
        sender.sendMessage(Component.text("Cloud Core PING sent."));
        return true;
    }

    private static boolean isKnownAction(String action) {
        return action.equalsIgnoreCase("ping")
                || action.equalsIgnoreCase("demo")
                || action.equalsIgnoreCase("demo-big")
                || action.equalsIgnoreCase("demo-huge")
                || action.equalsIgnoreCase("probe")
                || action.equalsIgnoreCase("boss")
                || action.equalsIgnoreCase("close")
                || action.equalsIgnoreCase("diagnostic")
                || action.equalsIgnoreCase("demo-hud")
                || action.equalsIgnoreCase("demo-tooltip")
                || action.equalsIgnoreCase("demo-container")
                || action.equalsIgnoreCase("demo-readonly")
                || action.equalsIgnoreCase("demo-inventory")
                || action.equalsIgnoreCase("demo-assets");
    }

    private void sendDemoInventory(Player player) {        String uiId = "cloudcore_demo_inventory";
        int token = random.nextInt();
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        session.lastUiId = uiId;
        UiOpen open = new UiOpen(uiId, 1, SurfaceType.PLAYER_INVENTORY, BindMode.PERSISTENT,
                token, RenderMode.SCREEN, 0, "default");
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_OPEN,
                UiCodec.encodeOpen(open, Limits.S2C)));
        String json = """
                {
                  "schema": 1,
                  "id": "cloudcore_demo_inventory",
                  "surface": "player_inventory",
                  "title": { "runs": [ { "text": "Cloud Core Inventory" } ] },
                  "size": [176, 166],
                  "elements": [
                    { "id": "inventory_bg", "type": "panel", "x": 0, "y": 0,
                      "width": "fill", "height": "fill",
                      "style": { "background": "#E0203040" } },
                    { "id": "inventory_hint", "type": "label", "x": 60, "y": 6,
                      "text": "Cloud Core", "color": "#FFFFFFFF" }
                  ]
                }
                """;
        UiDefinition definition = new UiDefinition(uiId, 1, 0,
                json.getBytes(StandardCharsets.UTF_8));
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION,
                        UiCodec.encodeDefinition(definition, Limits.S2C)));
            }
        }, 1L);
        getLogger().info("Sent player inventory UI_OPEN/UI_DEFINITION to " + player.getName());
    }

    private void sendDemoContainer(Player player) {
        openDemoChest(player, ContainerClickPolicy.Interaction.MANAGED);
    }

    /**
     * Opens a 27-slot chest backed by a {@link CloudInventoryHolder}. The document declares a
     * grid over slots 0-8, so {@code managed} lets the player take those items while
     * {@code readonly} cancels every click.
     */
    private void openDemoChest(Player player, ContainerClickPolicy.Interaction interaction) {
        String uiId = "cloudcore_demo_container";
        int token = random.nextInt();
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        session.lastUiId = uiId;
        UiOpen open = new UiOpen(uiId, 1, SurfaceType.CONTAINER, BindMode.NEXT_CONTAINER,
                token, RenderMode.SCREEN, 0, "demo-container");
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_OPEN,
                UiCodec.encodeOpen(open, Limits.S2C)));
        String json = """
                {
                  "schema": 1,
                  "id": "cloudcore_demo_container",
                  "surface": "container",
                  "title": { "runs": [ { "text": "Cloud Core Container" } ] },
                  "size": [176, 166],
                  "container": { "rows": 3, "interaction": "%s" },
                  "elements": [
                    { "id": "container_bg", "type": "panel", "x": 0, "y": 0,
                      "width": "fill", "height": "fill",
                      "style": { "background": "#E0203040" } },
                    { "id": "container_hint", "type": "label", "x": 8, "y": 6,
                      "text": "Cloud Core Container", "color": "#FFFFFFFF" },
                    { "id": "container_grid", "type": "inventory_grid", "x": 8, "y": 18,
                      "width": "fill", "height": 18,
                      "slots": [0, 8], "origin": [8, 18], "cell": [18, 18],
                      "columns": 9 }
                  ]
                }
                """.formatted(interaction.name().toLowerCase(java.util.Locale.ROOT));
        UiDefinition definition = new UiDefinition(uiId, 1, 0,
                json.getBytes(StandardCharsets.UTF_8));
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION,
                    UiCodec.encodeDefinition(definition, Limits.S2C)));
            getServer().getScheduler().runTaskLater(this, () -> {
                if (!player.isOnline()) {
                    return;
                }
                Set<Integer> declared = declaredSlotsOf(player, definition);
                CloudInventoryHolder holder = new CloudInventoryHolder(
                        player.getUniqueId(), uiId, interaction, declared);
                Inventory inventory = Bukkit.createInventory(holder, 27,
                        Component.text("Cloud Core Container"));
                holder.attach(inventory);
                inventory.setItem(0, new ItemStack(Material.DIAMOND, 5));
                inventory.setItem(13, new ItemStack(Material.GOLD_INGOT, 3));
                player.openInventory(inventory);
                getLogger().info("Opened demo chest for " + player.getName()
                        + " interaction=" + interaction + " declared=" + declared);
            }, 1L);
        }, 1L);
        getLogger().info("Sent container UI_OPEN/UI_DEFINITION to " + player.getName());
    }

    private Set<Integer> declaredSlotsOf(Player player, UiDefinition definition) {
        try {
            UiDocument document = UiDocumentJson.parse(definition.data());
            return ContainerClickPolicy.declaredSlots(document);
        } catch (PageLoadException exception) {
            getLogger().warning("Demo container document failed to parse for "
                    + player.getName() + ": " + exception.getMessage());
            return Set.of();
        }
    }

    private void sendTooltipOpen(Player player) {
        String uiId = "cloudcore_demo_tooltip";
        int token = random.nextInt();
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        session.lastUiId = uiId;
        UiOpen open = new UiOpen(uiId, 1, SurfaceType.TOOLTIP, BindMode.PERSISTENT,
                token, RenderMode.SURFACE_NATIVE, 0, "default");
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_OPEN,
                UiCodec.encodeOpen(open, Limits.S2C)));
        String json = """
                {
                  "schema": 1,
                  "id": "cloudcore_demo_tooltip",
                  "surface": "tooltip",
                  "title": { "runs": [ { "text": "Tooltip" } ] },
                  "size": [320, 180],
                  "elements": [
                    { "id": "tooltip_panel", "type": "panel", "x": 0, "y": 0,
                      "width": 320, "height": 180,
                      "style": { "background": "#F0182848" } }
                  ]
                }
                """;
        UiDefinition definition = new UiDefinition(uiId, 1, 0,
                json.getBytes(StandardCharsets.UTF_8));
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION,
                        UiCodec.encodeDefinition(definition, Limits.S2C)));
            }
        }, 1L);
        getLogger().info("Sent tooltip UI_OPEN/UI_DEFINITION to " + player.getName());
    }

    private void sendHudOpen(Player player) {
        String uiId = "cloudcore_demo_hud";
        int token = random.nextInt();
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        session.lastUiId = uiId;
        UiOpen open = new UiOpen(uiId, 1, SurfaceType.HUD, BindMode.PERSISTENT,
                token, RenderMode.SURFACE_NATIVE, 0, "default");
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_OPEN,
                UiCodec.encodeOpen(open, Limits.S2C)));
        String json = """
                {
                  "schema": 1,
                  "id": "cloudcore_demo_hud",
                  "surface": "hud",
                  "title": { "runs": [ { "text": "Cloud Core HUD" } ] },
                  "size": [320, 180],
                  "elements": [
                    { "id": "hud_panel", "type": "panel", "x": 0, "y": 0,
                      "width": 180, "height": 44,
                      "style": { "background": "#CC102030" } },
                    { "id": "hud_label", "type": "label", "x": 10, "y": 12,
                      "text": "Cloud Core HUD", "color": "#FFB8F0FF" },
                    { "id": "hud_hint", "type": "label", "x": 10, "y": 26,
                      "text": "surface=hud / persistent", "color": "#FF8FD0A0" }
                  ]
                }
                """;
        UiDefinition definition = new UiDefinition(uiId, 1, 0,
                json.getBytes(StandardCharsets.UTF_8));
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION,
                        UiCodec.encodeDefinition(definition, Limits.S2C)));
            }
        }, 1L);
        getLogger().info("Sent HUD UI_OPEN/UI_DEFINITION to " + player.getName());
    }

    private void sendDiagnostic(Player player) {
        Diagnostic diagnostic = new Diagnostic(DiagnosticSeverity.ERROR, "CC-PROBE-001",
                random.nextInt(), "probe", "Cloud Core diagnostic probe", "test detail");
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.DIAGNOSTIC,
                DiagnosticCodec.encode(diagnostic, Limits.S2C)));
        getLogger().info("Sent DIAGNOSTIC to " + player.getName());
    }

    private void sendClose(Player player) {
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        if (session.lastUiId == null) {
            getLogger().info("No probe UI open for " + player.getName());
            return;
        }
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_CLOSE,
                UiCodec.encodeClose(new UiClose(session.lastUiId, 0), Limits.S2C)));
        getLogger().info("Sent UI_CLOSE to " + player.getName() + " ui=" + session.lastUiId);
        session.lastUiId = null;
    }

    private void sendBoss(Player player) {
        int token = random.nextInt();
        String tokenHex = Integer.toHexString(token);
        String uiId = "cloudcore_demo_boss";
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        session.lastUiId = uiId;
        UiOpen open = new UiOpen(uiId, 1, SurfaceType.BOSS_BAR, BindMode.PERSISTENT,
                token, RenderMode.SURFACE_NATIVE, 0, tokenHex);
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_OPEN,
                UiCodec.encodeOpen(open, Limits.S2C)));
        String json = """
                {
                  "schema": 1,
                  "id": "cloudcore_demo_boss",
                  "surface": "boss_bar",
                  "title": { "runs": [ { "text": "Cloud Core Boss" } ] },
                  "size": [320, 180],
                  "elements": [
                    { "id": "boss_panel", "type": "panel", "x": 0, "y": 0,
                      "width": 320, "height": 180,
                      "style": { "background": "#F0101828" } }
                  ]
                }
                """;
        UiDefinition definition = new UiDefinition(uiId, 1, 0,
                json.getBytes(StandardCharsets.UTF_8));
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION,
                        UiCodec.encodeDefinition(definition, Limits.S2C)));
            }
        }, 1L);

        BossBar bar = Bukkit.createBossBar("\u0000CCB:" + tokenHex + "\u0000Cloud Core Boss",
                BarColor.RED, BarStyle.SOLID);
        bar.addPlayer(player);
        bar.setVisible(true);
        if (session.bossBar != null) {
            session.bossBar.removeAll();
        }
        session.bossBar = bar;
        getServer().getScheduler().runTaskLater(this, () -> {
            bar.removeAll();
            if (session.bossBar == bar) {
                session.bossBar = null;
            }
        }, 200L);
        getLogger().info("PROBE boss bar sent to " + player.getName() + " token=" + tokenHex);
    }

    private void sendProbe(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27,
                Component.text("Cloud Core Probe"));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!getConfig().getBoolean("inventory-probe", false)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        sendPing(player, session);
        getLogger().info("PROBE InventoryOpenEvent -> PING " + player.getName()
                + " inventory=" + event.getInventory().getType());
    }

    /**
     * Cancels clicks the container document does not allow. Runs at LOWEST so no other plugin
     * can observe or re-enable a forbidden click.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCloudInventoryClick(InventoryClickEvent event) {
        CloudInventoryHolder holder = holderOf(event.getInventory());
        if (holder == null) {
            return;
        }
        int rawSlot = event.getRawSlot();
        boolean top = rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize();
        if (ContainerClickPolicy.allow(holder.interaction(), top, rawSlot,
                holder.declaredSlots(), false)) {
            return;
        }
        event.setCancelled(true);
        getLogger().info("CANCELLED click ui=" + holder.uiId()
                + " interaction=" + holder.interaction()
                + " top=" + top + " slot=" + rawSlot
                + " declared=" + holder.declaredSlots()
                + " player=" + event.getWhoClicked().getName());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCloudInventoryDrag(InventoryDragEvent event) {
        CloudInventoryHolder holder = holderOf(event.getInventory());
        if (holder == null) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            boolean top = rawSlot >= 0 && rawSlot < topSize;
            if (!ContainerClickPolicy.allow(holder.interaction(), top, rawSlot,
                    holder.declaredSlots(), false)) {
                event.setCancelled(true);
                getLogger().info("CANCELLED drag ui=" + holder.uiId()
                        + " interaction=" + holder.interaction()
                        + " slots=" + event.getRawSlots()
                        + " player=" + event.getWhoClicked().getName());
                return;
            }
        }
    }

    private static CloudInventoryHolder holderOf(Inventory inventory) {
        return inventory != null && inventory.getHolder(false) instanceof CloudInventoryHolder holder
                ? holder
                : null;
    }

    private void sendDemoOpen(Player player, boolean big) {
        sendDemoOpen(player, big, big ? 48_000 : 0);
    }

    /** A document close to the 1 MiB frame limit, used to exercise fragmentation for real. */
    private void sendDemoHuge(Player player) {
        sendDemoOpen(player, true, 620_000);
    }

    /**
     * @param paddingBytes random bytes embedded as base64 so the document approaches the
     *                     1 MiB frame limit; 0 keeps the plain demo page
     */
    private void sendDemoOpen(Player player, boolean big, int paddingBytes) {
        String uiId = big && paddingBytes > 100_000 ? "cloudcore_demo_huge"
                : big ? "cloudcore_demo_big" : "cloudcore_demo";
        String padding = "";
        if (paddingBytes > 0) {
            byte[] bytes = new byte[paddingBytes];
            random.nextBytes(bytes);
            padding = ", \"debug_padding\": \""
                    + Base64.getEncoder().encodeToString(bytes) + "\"";
        }
        int token = random.nextInt();
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        session.lastUiId = uiId;
        UiOpen open = new UiOpen(uiId, 1, SurfaceType.CUSTOM_SCREEN,
                BindMode.NONE, token, RenderMode.SCREEN, 0, big ? "demo-big" : "demo");
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_OPEN,
                UiCodec.encodeOpen(open, Limits.S2C)));

        String json = """
                {
                  "schema": 1,
                  "id": "%s",
                  "surface": "custom_screen",
                  "title": { "runs": [ { "text": "Cloud Core Demo" } ] },
                  "theme": "dark",
                  "size": [320, 180],
                  "scale": 1.0,
                  "elements": [
                    { "id": "root", "type": "panel", "x": 0, "y": 0,
                      "width": "fill", "height": "fill"%s,
                      "style": { "background": "#CC202830" } },
                    { "id": "title", "type": "label", "x": 16, "y": 18,
                      "text": "Cloud Core Demo", "color": "#FFFFFFFF" },
                    { "id": "hint", "type": "label", "x": 16, "y": 38,
                      "text": "Native RenderList / protocol 1.1", "color": "#FF9AD0FF" },
                    { "id": "button", "type": "button", "x": 16, "y": 70,
                      "width": 120, "height": 24, "text": "Button",
                      "style": { "background": "#FF3C6E9F" } },
                    { "id": "logo", "type": "image", "x": 160, "y": 70,
                      "width": 64, "height": 64, "texture": "local:test/red.png" }
                  ]
                }
                """.formatted(uiId, padding);
        UiDefinition definition = new UiDefinition(uiId, 1, 0,
                json.getBytes(StandardCharsets.UTF_8));
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION,
                        UiCodec.encodeDefinition(definition, Limits.S2C)));
            }
        }, 1L);
        getLogger().info("Sent demo UI_OPEN/UI_DEFINITION to " + player.getName()
                + " bytes=" + json.length());
    }

    private void sendDemoAssets(Player player) {
        String uiId = "cloudcore_demo_assets";
        int token = random.nextInt();
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        session.lastUiId = uiId;
        UiOpen open = new UiOpen(uiId, 1, SurfaceType.CUSTOM_SCREEN,
                BindMode.NONE, token, RenderMode.SCREEN, 0, "demo-assets");
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_OPEN,
                UiCodec.encodeOpen(open, Limits.S2C)));
        String json = """
                {
                  "schema": 1,
                  "id": "cloudcore_demo_assets",
                  "surface": "custom_screen",
                  "title": { "runs": [ { "text": "Cloud Core Assets" } ] },
                  "size": [320, 180],
                  "elements": [
                    { "id": "root", "type": "panel", "x": 0, "y": 0,
                      "width": "fill", "height": "fill",
                      "style": { "background": "#CC202830" } },
                    { "id": "title", "type": "label", "x": 16, "y": 14,
                      "text": "item_icon / pack / builtin", "color": "#FFFFFFFF" },
                    { "id": "diamond", "type": "item_icon", "x": 16, "y": 36,
                      "width": 16, "height": 16,
                      "item": { "id": "minecraft:diamond", "count": 5 },
                      "show_count": true },
                    { "id": "emerald", "type": "item_icon", "x": 48, "y": 30,
                      "width": 32, "height": 32,
                      "item": { "id": "minecraft:emerald", "count": 1 },
                      "scale": 2.0 },
                    { "id": "sword", "type": "item_icon", "x": 96, "y": 36,
                      "width": 16, "height": 16,
                      "item": { "id": "minecraft:netherite_sword", "count": 1 } },
                    { "id": "pack_block", "type": "image", "x": 16, "y": 96,
                      "width": 32, "height": 32,
                      "texture": "pack:minecraft:block/diamond_block" },
                    { "id": "builtin_error", "type": "image", "x": 64, "y": 96,
                      "width": 32, "height": 32,
                      "texture": "builtin:error" },
                    { "id": "missing", "type": "image", "x": 112, "y": 96,
                      "width": 32, "height": 32,
                      "texture": "pack:cloudcore:missing/texture" },
                    { "id": "local_red", "type": "image", "x": 160, "y": 96,
                      "width": 32, "height": 32,
                      "texture": "local:test/red.png" }
                  ]
                }
                """;
        UiDefinition definition = new UiDefinition(uiId, 1, 0,
                json.getBytes(StandardCharsets.UTF_8));
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION,
                        UiCodec.encodeDefinition(definition, Limits.S2C)));
            }
        }, 1L);
        getLogger().info("Sent asset demo UI_OPEN/UI_DEFINITION to " + player.getName());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Session removed = sessions.remove(event.getPlayer().getUniqueId());
        if (removed != null) {
            removed.assembler.clear();
            if (removed.bossBar != null) {
                removed.bossBar.removeAll();
            }
        }
    }

    private static final class Session {
        private final FragmentAssembler assembler = new FragmentAssembler(Limits.C2S);
        private Hello hello;
        private boolean pingPending;
        private int pingNonce;
        private long pingSentNanos;
        private BossBar bossBar;
        private String lastUiId;
    }
}
