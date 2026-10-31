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
import com.cloudcore.protocol.ElementUpdate;
import com.cloudcore.protocol.ElementUpdateCodec;
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
import com.cloudcore.bukkit.texture.TextureProxy;
import com.cloudcore.server.surface.ContainerClickPolicy;
import com.cloudcore.server.update.ElementDiffer;
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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

public final class CloudCorePlugin extends JavaPlugin
        implements PluginMessageListener, CommandExecutor, org.bukkit.command.TabCompleter,
        Listener {

    public static final String CHANNEL = "cloudcore:main";
    private static final int SERVER_FEATURES = FeatureFlags.NONE;
    private static final String PING_PERMISSION = "cloudcore.admin";

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private java.nio.file.Path pagesRoot;
    private com.cloudcore.bukkit.PageWatcher pageWatcher;
    /**
     * Compiled pages per player: the document is expanded against that player's state, so the
     * element ids and action table belong to one player and must not be shared.
     */
    private final Map<UUID, Map<String, com.cloudcore.api.page.PageLoader.CompiledPage>>
            compiledPages = new ConcurrentHashMap<>();
    private final com.cloudcore.server.surface.EventRateLimiter eventLimiter =
            new com.cloudcore.server.surface.EventRateLimiter();
    /** Addon actions registered through {@link com.cloudcore.bukkit.api.CloudCoreApi}. */
    private final Map<String, com.cloudcore.bukkit.api.ActionHandler> actionHandlers =
            new ConcurrentHashMap<>();
    private final Map<String, com.cloudcore.bukkit.api.PlaceholderResolver> placeholderResolvers =
            new ConcurrentHashMap<>();
    private final Map<String, com.cloudcore.bukkit.api.PageProvider> pageProviders =
            new ConcurrentHashMap<>();
    /** Addon surface providers; the built-in surfaces keep their own send paths. */
    private final com.cloudcore.server.surface.SurfaceProviderRegistry surfaceProviders =
            new com.cloudcore.server.surface.SurfaceProviderRegistry();
    /** Addon event listeners. A broken listener is logged, never allowed to break a UI. */
    private final com.cloudcore.server.event.AddonEventBus addonEvents =
            new com.cloudcore.server.event.AddonEventBus((type, error) -> getLogger().log(
                    Level.WARNING, "Addon listener for " + type.getSimpleName() + " failed",
                    error));
    private final Placeholders placeholders = new Placeholders(this, placeholderResolvers);
    private com.cloudcore.server.state.PlayerStateStore stateStore;
    private com.cloudcore.bukkit.slot.AccessorySlots accessorySlots;
    private com.cloudcore.bukkit.texture.TextureProxy textureProxy;
    private static final Set<String> BUILT_IN_ACTIONS = Set.of(
            "message", "close_ui", "close", "set_state", "if", "delay", "command",
            "open_page", "sound", "refresh");
    private long playerRefreshBudget = 2_000_000L;
    private long globalRefreshBudget = 10_000_000L;
    private org.bukkit.scheduler.BukkitTask refreshTask;
    private long refreshTick;
    /** Demo pages copied into {@code plugins/CloudCore/pages/} when missing. */
    private static final java.util.List<String> DEMO_PAGES = java.util.List.of(
            "example", "shop", "progress_demo", "scroll_demo", "actions_demo", "papi_demo",
            "papi_refresh", "tooltip_rpg", "rpg_hud", "inventory_skin", "slider_demo",
            "boss_dnf");
    private final com.cloudcore.bukkit.api.CloudCoreApi api = new Api();

    @Override
    public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getServicesManager().register(com.cloudcore.bukkit.api.CloudCoreApi.class,
                api, this, org.bukkit.plugin.ServicePriority.Normal);
        if (getCommand("cloudcore") != null) {
            getCommand("cloudcore").setExecutor(this);
            getCommand("cloudcore").setTabCompleter(this);
        } else {
            getLogger().warning("Command 'cloudcore' is missing from plugin.yml");
        }
        saveDefaultConfig();
        accessorySlots = new com.cloudcore.bukkit.slot.AccessorySlots(this);
        playerRefreshBudget = Math.max(1L,
                getConfig().getLong("placeholders.player-budget-ms", 2L)) * 1_000_000L;
        globalRefreshBudget = Math.max(1L,
                getConfig().getLong("placeholders.global-budget-ms", 10L)) * 1_000_000L;
        textureProxy = createTextureProxy();
        preparePagesDirectory();
        startPageWatcher();
        startStatePersistence();
        refreshTask = getServer().getScheduler().runTaskTimer(this, this::tickPageRefresh, 1L, 1L);
        getLogger().info("Cloud Core transport online on " + CHANNEL
                + " (protocol " + ProtocolVersion.MAJOR + "." + ProtocolVersion.MINOR + ")");
    }

    /**
     * Creates {@code plugins/CloudCore/pages/} and drops the demo pages there once each, so an
     * admin can start from working YAML instead of the JSON probe documents. Existing files are
     * never overwritten: a server's own edits win.
     */
    private void preparePagesDirectory() {
        pagesRoot = getDataFolder().toPath().resolve("pages");
        try {
            java.nio.file.Files.createDirectories(pagesRoot);
            for (String name : DEMO_PAGES) {
                java.nio.file.Path file = pagesRoot.resolve(name + ".yml");
                if (java.nio.file.Files.exists(file)) {
                    continue;
                }
                try (java.io.InputStream input = getResource("pages/" + name + ".yml")) {
                    if (input != null) {
                        java.nio.file.Files.copy(input, file);
                        getLogger().info("Wrote demo page " + file.getFileName() + " to " + pagesRoot);
                    }
                }
            }
        } catch (java.io.IOException exception) {
            getLogger().log(Level.WARNING, "Cannot prepare pages directory", exception);
        }
    }

    /** Hot reload of authored pages; the callback hops back to the main thread. */
    private void startPageWatcher() {
        if (!getConfig().getBoolean("pages.watch", true) || pagesRoot == null) {
            return;
        }
        try {
            pageWatcher = new com.cloudcore.bukkit.PageWatcher(pagesRoot, () -> {
                if (!isEnabled()) {
                    return;
                }
                getServer().getScheduler().runTask(this,
                        () -> reloadPages(getServer().getConsoleSender()));
            }, getLogger());
            getLogger().info("Watching " + pagesRoot + " for page changes");
        } catch (java.io.IOException exception) {
            getLogger().log(Level.WARNING, "Cannot watch the pages directory", exception);
        }
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        if (stateStore != null) {
            flushDirtyState();
        }
        if (textureProxy != null) {
            textureProxy.close();
            textureProxy = null;
        }
        getServer().getServicesManager().unregisterAll(this);
        actionHandlers.clear();
        placeholderResolvers.clear();
        pageProviders.clear();
        surfaceProviders.clear();
        addonEvents.clear();
        if (pageWatcher != null) {
            pageWatcher.close();
            pageWatcher = null;
        }
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
            case Opcode.EVENT -> handleEvent(player, session, frame);
            case Opcode.TEXTURE_REQUEST -> handleTextureRequest(player, session, frame);
            default -> getLogger().info("Unhandled C2S opcode=0x"
                    + Integer.toHexString(frame.opcode()) + " bytes=" + frame.bodyLength()
                    + " player=" + player.getUniqueId());
        }
    }

    /**
     * Executes the actions the page compiler attached to this action id. Action bodies never
     * travel to the client; the client only echoes the id it received in the document.
     */
    private void handleEvent(Player player, Session session, Frame frame) {
        com.cloudcore.protocol.UiEvent event;
        try {
            event = com.cloudcore.protocol.EventCodec.decode(frame.body(), Limits.C2S);
        } catch (ProtocolException exception) {
            getLogger().warning("Malformed EVENT from " + player.getName() + ": "
                    + exception.getMessage());
            return;
        }
        com.cloudcore.api.page.PageLoader.CompiledPage page = compiledPage(player.getUniqueId(),
                event.uiId());
        java.util.List<com.cloudcore.api.page.PageLoader.PageAction> actions =
                page == null ? null : page.actions().get(event.actionId());
        publishElementEvent(player, event);
        if (actions == null) {
            getLogger().warning("EVENT for unknown action ui=" + event.uiId()
                    + " element=" + event.elementId() + " action=" + event.actionId()
                    + " player=" + player.getName());
            sendEventResult(player, event.requestId(),
                    com.cloudcore.protocol.EventResult.UNKNOWN_ELEMENT);
            publishActionResult(player, event, com.cloudcore.protocol.EventResult.UNKNOWN_ELEMENT);
            return;
        }
        if (!eventLimiter.tryAcquire(player.getUniqueId())) {
            getLogger().warning("Rate limited EVENT from " + player.getName());
            sendEventResult(player, event.requestId(),
                    com.cloudcore.protocol.EventResult.RATE_LIMITED);
            publishActionResult(player, event, com.cloudcore.protocol.EventResult.RATE_LIMITED);
            return;
        }
        getLogger().info("EVENT ui=" + event.uiId() + " element=" + event.elementId()
                + " actions=" + actions.size() + " player=" + player.getName());
        int status = com.cloudcore.protocol.EventResult.OK;
        for (com.cloudcore.api.page.PageLoader.PageAction action : actions) {
            ActionOutcome outcome = executeAction(player, page, action, event.payload(),
                    event.elementId());
            if (outcome == ActionOutcome.DENIED) {
                // A missing permission rejects the whole click, not just that one action.
                status = com.cloudcore.protocol.EventResult.REJECTED;
                break;
            }
            if (outcome == ActionOutcome.FAILED) {
                status = com.cloudcore.protocol.EventResult.REJECTED;
            }
        }
        sendEventResult(player, event.requestId(), status);
        publishActionResult(player, event, status);
        PageRefresh refresh = session.refreshes.get(event.uiId());
        if (refresh != null && refresh.plan.hasEvent()) {
            refresh.eventDue = true;
        }
    }

    /** Copies the wire payload into plain values; protocol types stay on the wire boundary. */
    private void publishElementEvent(Player player, com.cloudcore.protocol.UiEvent event) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, com.cloudcore.protocol.WireValue> entry
                : event.payload().entrySet()) {
            payload.put(entry.getKey(), plainValue(entry.getValue()));
        }
        addonEvents.publish(new com.cloudcore.api.event.ElementEvent(player.getUniqueId(),
                event.uiId(), event.elementId(), event.actionId(), payload));
    }

    private void publishActionResult(Player player, com.cloudcore.protocol.UiEvent event,
            int status) {
        addonEvents.publish(new com.cloudcore.api.event.ActionResultEvent(player.getUniqueId(),
                event.uiId(), event.elementId(), event.actionId(), status));
    }

    private void sendEventResult(Player player, int requestId, int status) {
        com.cloudcore.protocol.EventResult result = new com.cloudcore.protocol.EventResult(
                requestId, status, com.cloudcore.protocol.EventResult.nameOf(status));
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.EVENT_RESULT,
                com.cloudcore.protocol.EventResultCodec.encode(result, Limits.S2C)));
    }

    /** How one action finished; a denied permission aborts the rest of the click. */
    private enum ActionOutcome { OK, FAILED, DENIED }

    private ActionOutcome executeAction(Player player,
            com.cloudcore.api.page.PageLoader.CompiledPage page,
            com.cloudcore.api.page.PageLoader.PageAction action,
            Map<String, com.cloudcore.protocol.WireValue> payload,
            String elementId) {
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        Map<String, Object> sessionState = session.pageState.computeIfAbsent(
                session.pageNames.getOrDefault(page.document().id(), page.document().id()),
                ignored -> new ConcurrentHashMap<>());
        // Expressions see document defaults overlaid with the player's changes; writes go
        // to the session map only.
        Map<String, Object> state = new java.util.LinkedHashMap<>(page.state());
        state.putAll(sessionState);
        Object permission = action.params().get("permission");
        if (permission instanceof String node && !node.isBlank()
                && !player.hasPermission(resolveState(node, state, payload))) {
            getLogger().info("Denied action '" + action.type() + "' for " + player.getName()
                    + " on page " + page.document().id() + " element " + elementId
                    + " (missing " + node + ")");
            return ActionOutcome.DENIED;
        }
        switch (action.type().toLowerCase(java.util.Locale.ROOT)) {
            case "close_ui", "close" -> sendClose(player);
            case "open_page" -> {
                Object target = action.params().getOrDefault("page",
                        action.params().getOrDefault("value", null));
                if (target == null) {
                    getLogger().warning("open_page without page in page "
                            + page.document().id());
                    return ActionOutcome.FAILED;
                }
                String name = resolveState(String.valueOf(target), state, payload);
                // Close the page that is being replaced so the client does not keep a stale id.
                if (session.lastUiId != null && !session.lastUiId.equals(name)) {
                    sendClose(player);
                }
                sendPage(player, name);
            }
            case "sound" -> {
                Object sound = action.params().getOrDefault("sound",
                        action.params().getOrDefault("value", null));
                if (sound == null) {
                    getLogger().warning("sound without sound in page " + page.document().id());
                    return ActionOutcome.FAILED;
                }
                String key = resolveState(String.valueOf(sound), state, payload);
                float volume = action.params().get("volume") instanceof Number number
                        ? Math.max(0.0f, Math.min(4.0f, number.floatValue())) : 1.0f;
                float pitch = action.params().get("pitch") instanceof Number number
                        ? Math.max(0.5f, Math.min(2.0f, number.floatValue())) : 1.0f;
                try {
                    player.playSound(player.getLocation(), key, volume, pitch);
                } catch (RuntimeException exception) {
                    getLogger().warning("sound action failed for '" + key + "': "
                            + exception.getMessage());
                    return ActionOutcome.FAILED;
                }
            }
            case "message" -> {
                Object text = action.params().getOrDefault("value",
                        action.params().getOrDefault("text", ""));
                player.sendMessage(Component.text(
                        String.valueOf(evaluateOrResolve(String.valueOf(text), state, payload))));
            }
            case "command" -> {
                Object raw = action.params().getOrDefault("command",
                        action.params().getOrDefault("value", ""));
                String command = resolveState(String.valueOf(raw), state, payload);
                if (!commandAllowed(command)) {
                    getLogger().warning("Blocked command action '" + command + "' from page "
                            + page.document().id() + " for " + player.getName());
                    return ActionOutcome.FAILED;
                }
                boolean asConsole = "console".equalsIgnoreCase(
                        String.valueOf(action.params().getOrDefault("as", "player")));
                String line = command.startsWith("/") ? command.substring(1) : command;
                getServer().getScheduler().runTask(this, () -> getServer().dispatchCommand(
                        asConsole ? getServer().getConsoleSender() : player, line));
                getLogger().info("Ran action command '" + line + "' as "
                        + (asConsole ? "console" : player.getName()));
            }
            case "set_state" -> {
                Object key = action.params().get("key");
                if (key == null) {
                    getLogger().warning("set_state without key in page " + page.document().id());
                    return ActionOutcome.FAILED;
                }
                Object raw = action.params().get("value");
                Object value = raw instanceof String text
                        ? evaluateOrResolve(text, state, payload) : raw;
                Object previous = sessionState.put(String.valueOf(key), normalise(value));
                // Recompiling and pushing an identical document would redraw the screen for
                // nothing; repeat events (hover, scroll at the ends) depend on this guard.
                if (!java.util.Objects.equals(previous, sessionState.get(String.valueOf(key)))) {
                    session.stateDirty = true;
                    session.stateVersion.incrementAndGet();
                    refreshPage(player, session, page);
                }
            }
            case "refresh" -> refreshPage(player, session, page);
            case "if" -> {
                boolean condition = truthy(evaluateCondition(
                        action.params().get("condition"), state, payload));
                Object branch = action.params().get(condition ? "then" : "else");
                ActionOutcome outcome = ActionOutcome.OK;
                for (com.cloudcore.api.page.PageLoader.PageAction nested : toActions(branch)) {
                    ActionOutcome nestedOutcome = executeAction(player, page, nested, payload,
                            elementId);
                    if (nestedOutcome == ActionOutcome.DENIED) {
                        return ActionOutcome.DENIED;
                    }
                    if (nestedOutcome == ActionOutcome.FAILED) {
                        outcome = ActionOutcome.FAILED;
                    }
                }
                return outcome;
            }
            case "delay" -> {
                Object ticksValue = action.params().getOrDefault("ticks", 20);
                long ticks = ticksValue instanceof Number number
                        ? Math.max(1, number.longValue()) : 20;
                java.util.List<com.cloudcore.api.page.PageLoader.PageAction> nested =
                        toActions(action.params().get("then"));
                getServer().getScheduler().runTaskLater(this, () -> {
                    if (player.isOnline()) {
                        for (com.cloudcore.api.page.PageLoader.PageAction delayed : nested) {
                            executeAction(player, page, delayed, payload, elementId);
                        }
                    }
                }, ticks);
            }
            default -> {
                return runCustomAction(player, page, action, state, session, elementId);
            }
        }
        return ActionOutcome.OK;
    }

    /**
     * Runs an action registered through {@code CloudCoreApi#registerActionHandler}. Built-in
     * types never reach here; an unknown type is logged and rejects the click.
     */
    private ActionOutcome runCustomAction(Player player,
            com.cloudcore.api.page.PageLoader.CompiledPage page,
            com.cloudcore.api.page.PageLoader.PageAction action,
            Map<String, Object> state, Session session, String elementId) {
        String name = action.type().toLowerCase(java.util.Locale.ROOT);
        com.cloudcore.bukkit.api.ActionHandler handler = actionHandlers.get(name);
        if (handler == null) {
            getLogger().warning("Unsupported action '" + action.type() + "' from page "
                    + page.document().id() + "; register it through CloudCoreApi");
            return ActionOutcome.FAILED;
        }
        String permission = handler.permission();
        if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
            getLogger().info("Denied action '" + name + "' for " + player.getName()
                    + " (missing " + permission + ")");
            return ActionOutcome.DENIED;
        }
        try {
            handler.handle(new HandlerContext(player, page, elementId, state, session),
                    java.util.Collections.unmodifiableMap(action.params()));
            return ActionOutcome.OK;
        } catch (Exception exception) {
            getLogger().log(Level.WARNING, "Action '" + name + "' from page "
                    + page.document().id() + " element " + elementId + " failed", exception);
            return ActionOutcome.FAILED;
        }
    }

    /**
     * Command actions are off unless {@code actions.commands.enabled} is set; the first word
     * must appear in {@code actions.commands.allowlist}. This keeps an authored page from
     * escalating to arbitrary server commands.
     */
    private boolean commandAllowed(String command) {
        if (!getConfig().getBoolean("actions.commands.enabled", false)) {
            return false;
        }
        String line = command.startsWith("/") ? command.substring(1) : command;
        String verb = line.trim().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        for (String allowed : getConfig().getStringList("actions.commands.allowlist")) {
            if (allowed.trim().toLowerCase(java.util.Locale.ROOT).equals(verb)) {
                return true;
            }
        }
        return false;
    }

    /** Applies addon resolvers and PlaceholderAPI to the visible text. */
    private UiDocument expandPlaceholders(Player player, UiDocument document) {
        return placeholders.expand(player, document,
                getConfig().getBoolean("placeholders.enabled", true));
    }

    private com.cloudcore.api.page.PageLoader.CompiledPage compiledPage(java.util.UUID player,
            String uiId) {
        Map<String, com.cloudcore.api.page.PageLoader.CompiledPage> pages =
                compiledPages.get(player);
        return pages == null ? null : pages.get(uiId);
    }

    private void putCompiledPage(java.util.UUID player,
            com.cloudcore.api.page.PageLoader.CompiledPage page) {
        compiledPages.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>())
                .put(page.document().id(), page);
    }

    /** Recompiles the page with the current state and sends it as a document update. */
    private void refreshPage(Player player, Session session,
            com.cloudcore.api.page.PageLoader.CompiledPage previous) {
        String uiId = previous.document().id();
        String name = session.pageNames.get(uiId);
        if (name == null) {
            return;
        }
        java.nio.file.Path pageFile = resolvePageFile(name);
        if (pageFile == null) {
            getLogger().warning("Cannot refresh page '" + name + "': file missing");
            return;
        }
        Map<String, Object> state = session.pageState.computeIfAbsent(name,
                ignored -> new ConcurrentHashMap<>());
        com.cloudcore.api.page.PageLoader.CompiledPage compiled;
        try {
            compiled = com.cloudcore.api.page.PageLoader.load(pagesRoot, pageFile, state);
        } catch (com.cloudcore.api.page.PageLoadException exception) {
            getLogger().warning("Refresh of '" + name + "' failed: " + exception.getMessage());
            return;
        }
        putCompiledPage(player.getUniqueId(), compiled);
        UiDocument expanded = expandPlaceholders(player, compiled.document());
        deliverRefresh(player, session, name, compiled, expanded);
    }

    /** Diffs one already-expanded revision against the last sent document and delivers it. */
    private void deliverRefresh(Player player, Session session, String name,
            com.cloudcore.api.page.PageLoader.CompiledPage compiled, UiDocument expanded) {
        String uiId = expanded.id();
        UiDocument previousSent = session.sentDocuments.get(uiId);
        if (previousSent != null && supportsIncremental(expanded.surface())) {
            ElementDiffer.Result diff = ElementDiffer.diff(previousSent, expanded);
            if (diff.mode() == ElementDiffer.Mode.UNCHANGED) {
                getLogger().log(Level.FINE, "Page '" + name + "' unchanged for " + player.getName());
                return;
            }
            if (diff.mode() == ElementDiffer.Mode.INCREMENTAL) {
                int revision = session.pageRevisions.merge(uiId, 1, Integer::sum);
                session.sentDocuments.put(uiId, expanded);
                // A style/texture change can reference an asset the client has not seen yet;
                // resending refs is cheap and keeps the incremental path self-contained.
                sendTextureRefs(player, compiled);
                ElementUpdate update = new ElementUpdate(uiId, revision, diff.entries());
                sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.ELEMENT_UPDATE,
                        ElementUpdateCodec.encode(update, Limits.S2C)));
                getLogger().info("Updated page '" + name + "' revision=" + revision
                        + " incrementally entries=" + diff.entries().size()
                        + " for " + player.getName());
                return;
            }
        }
        int revision = session.pageRevisions.merge(uiId, 1, Integer::sum);
        String json = com.cloudcore.api.document.UiDocumentJson.write(expanded);
        UiDefinition definition = new UiDefinition(uiId, revision, 0,
                json.getBytes(StandardCharsets.UTF_8));
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION,
                UiCodec.encodeDefinition(definition, Limits.S2C)));
        session.sentDocuments.put(uiId, expanded);
        getLogger().info("Updated page '" + name + "' revision=" + revision
                + " state=" + compiled.state() + " for " + player.getName());
    }

    /**
     * Compiles and expands one page off the main thread, then diffs and sends on it. A page
     * with {@code placeholders.async: true} opts into this path; PlaceholderAPI extensions
     * that are not thread-safe must stay on synchronous pages.
     */
    private void startAsyncRefresh(Player player, Session session, String uiId,
            PageRefresh refresh) {
        String name = session.pageNames.get(uiId);
        if (name == null) {
            return;
        }
        java.nio.file.Path pageFile = resolvePageFile(name);
        if (pageFile == null) {
            getLogger().warning("Cannot refresh page '" + name + "': file missing");
            return;
        }
        Map<String, Object> state = new java.util.LinkedHashMap<>(session.pageState
                .computeIfAbsent(name, ignored -> new ConcurrentHashMap<>()));
        long version = session.stateVersion.get();
        refresh.inFlight = true;
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            com.cloudcore.api.page.PageLoader.CompiledPage compiled;
            UiDocument expanded;
            try {
                compiled = com.cloudcore.api.page.PageLoader.load(pagesRoot, pageFile, state);
                expanded = expandPlaceholders(player, compiled.document());
            } catch (Throwable failure) {
                getLogger().log(Level.WARNING, "Async refresh of '" + name + "' failed", failure);
                getServer().getScheduler().runTask(this, () -> refresh.inFlight = false);
                return;
            }
            com.cloudcore.api.page.PageLoader.CompiledPage ready = compiled;
            UiDocument document = expanded;
            getServer().getScheduler().runTask(this, () -> {
                refresh.inFlight = false;
                if (!player.isOnline()
                        || session.refreshes.get(uiId) != refresh
                        || session.stateVersion.get() != version) {
                    getLogger().log(Level.FINE, "Dropped stale async refresh for '" + name + "'");
                    return;
                }
                putCompiledPage(player.getUniqueId(), ready);
                deliverRefresh(player, session, name, ready, document);
            });
        });
    }

    /** Surfaces whose client adapter can replace a document in place. */
    private static boolean supportsIncremental(SurfaceType surface) {
        return surface == SurfaceType.CUSTOM_SCREEN || surface == SurfaceType.HUD;
    }

    /**
     * Runs on the server tick. Both budgets are soft: a page that misses its slot waits for
     * the next interval instead of blocking gameplay. Refreshes only recompile pages that
     * opted in; static pages cost one empty-map check per tick.
     */
    private void tickPageRefresh() {
        refreshTick++;
        long globalStart = System.nanoTime();
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            if (session.refreshes.isEmpty()) {
                continue;
            }
            Player player = getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }
            long playerStart = System.nanoTime();
            for (Map.Entry<String, PageRefresh> scheduled : session.refreshes.entrySet()) {
                PageRefresh refresh = scheduled.getValue();
                if (!refresh.eventDue && !refresh.plan.due(refreshTick)) {
                    continue;
                }
                refresh.eventDue = false;
                com.cloudcore.api.page.PageLoader.CompiledPage compiled =
                        compiledPage(entry.getKey(), scheduled.getKey());
                if (compiled == null) {
                    continue;
                }
                if (refresh.plan.async()) {
                    if (!refresh.inFlight) {
                        startAsyncRefresh(player, session, scheduled.getKey(), refresh);
                    }
                    continue;
                }
                refreshPage(player, session, compiled);
                if (System.nanoTime() - playerStart >= playerRefreshBudget
                        || System.nanoTime() - globalStart >= globalRefreshBudget) {
                    break;
                }
            }
            if (System.nanoTime() - globalStart >= globalRefreshBudget) {
                break;
            }
        }
    }

    /**
     * Replaces {@code ${value}} and friends from the event payload first, then
     * {@code ${state.key}} / {@code ${key}} from the page state.
     */
    private static String resolveState(String text, Map<String, Object> state,
            Map<String, com.cloudcore.protocol.WireValue> payload) {
        String result = text;
        for (Map.Entry<String, com.cloudcore.protocol.WireValue> entry : payload.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", payloadText(entry.getValue()));
        }
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            result = result.replace("${state." + entry.getKey() + "}",
                    String.valueOf(entry.getValue()));
            result = result.replace("${" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    /** Variables visible to expressions: page state plus the event payload. */
    private static Map<String, Object> expressionVariables(Map<String, Object> state,
            Map<String, com.cloudcore.protocol.WireValue> payload) {
        Map<String, Object> variables = new java.util.LinkedHashMap<>();
        variables.put("state", state);
        for (Map.Entry<String, com.cloudcore.protocol.WireValue> entry : payload.entrySet()) {
            variables.put(entry.getKey(), plainValue(entry.getValue()));
        }
        return variables;
    }

    private static Object plainValue(com.cloudcore.protocol.WireValue value) {
        return switch (value) {
            case com.cloudcore.protocol.WireValue.StringValue string -> string.value();
            case com.cloudcore.protocol.WireValue.IntValue number -> (double) number.value();
            case com.cloudcore.protocol.WireValue.LongValue number -> (double) number.value();
            case com.cloudcore.protocol.WireValue.FloatValue number -> (double) number.value();
            case com.cloudcore.protocol.WireValue.DoubleValue number -> number.value();
            case com.cloudcore.protocol.WireValue.BoolValue flag -> flag.value();
            default -> "";
        };
    }

    /** Evaluates {@code ${expr}} with the expression engine, falling back to substitution. */
    private static Object evaluateOrResolve(String text, Map<String, Object> state,
            Map<String, com.cloudcore.protocol.WireValue> payload) {
        String trimmed = text.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String expression = trimmed.substring(2, trimmed.length() - 1);
            try {
                return com.cloudcore.api.expression.ExpressionEvaluator.evaluate(expression,
                        expressionVariables(state, payload));
            } catch (com.cloudcore.api.expression.ExpressionEvaluator.ExpressionException ignored) {
                // Not an expression (plain path or literal): fall through to substitution.
            }
        }
        return resolveState(text, state, payload);
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0;
        }
        return value != null && !"false".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean evaluateCondition(Object raw, Map<String, Object> state,
            Map<String, com.cloudcore.protocol.WireValue> payload) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof Boolean flag) {
            return flag;
        }
        return truthy(evaluateOrResolve(String.valueOf(raw), state, payload));
    }

    /** Converts a nested {@code then}/{@code else} list into executable actions. */
    private static java.util.List<com.cloudcore.api.page.PageLoader.PageAction> toActions(
            Object raw) {
        java.util.List<com.cloudcore.api.page.PageLoader.PageAction> actions =
                new java.util.ArrayList<>();
        if (!(raw instanceof java.util.List<?> list)) {
            return actions;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Object type = map.get("action");
            if (!(type instanceof String name) || name.isBlank()) {
                continue;
            }
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> param : map.entrySet()) {
                if (!"action".equals(String.valueOf(param.getKey()))) {
                    params.put(String.valueOf(param.getKey()), param.getValue());
                }
            }
            actions.add(new com.cloudcore.api.page.PageLoader.PageAction(name, params));
        }
        return actions;
    }

    /** Booleans and numbers arrive from payloads as text after substitution. */
    private static Object normalise(Object value) {
        if (value instanceof String text) {
            return switch (text) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                default -> text;
            };
        }
        return value;
    }

    private static String payloadText(com.cloudcore.protocol.WireValue value) {
        return switch (value) {
            case com.cloudcore.protocol.WireValue.StringValue string -> string.value();
            case com.cloudcore.protocol.WireValue.IntValue number -> String.valueOf(number.value());
            case com.cloudcore.protocol.WireValue.LongValue number -> String.valueOf(number.value());
            case com.cloudcore.protocol.WireValue.FloatValue number -> String.valueOf(number.value());
            case com.cloudcore.protocol.WireValue.DoubleValue number -> String.valueOf(number.value());
            case com.cloudcore.protocol.WireValue.BoolValue flag -> String.valueOf(flag.value());
            default -> "";
        };
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
        Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.refreshes.remove(closed.uiId());
        }
        surfaceProviders.close(player.getUniqueId(), closed.uiId());
        addonEvents.publish(new com.cloudcore.api.event.SessionCloseEvent(player.getUniqueId(),
                closed.uiId(), "client"));
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
        applyOnJoin(player);
    }

    /**
     * Applies every ambient page once the handshake is up, so a player sees the server's HUD,
     * inventory skin, tooltip theme and boss-bar skin immediately instead of after a command.
     *
     * <p>"Ambient" means the surfaces that live alongside normal gameplay — {@link
     * SurfaceType#HUD}, {@link SurfaceType#PLAYER_INVENTORY}, {@link SurfaceType#TOOLTIP},
     * {@link SurfaceType#BOSS_BAR}. Screens and containers are deliberately excluded: those are
     * opened by a trigger (a command, a container opening), not on join.
     *
     * <p>No config list is needed; a page opts out with {@code surface_options.on_join: false}.
     * If {@code apply-on-join.pages} is set it wins, so an operator can still pin an explicit
     * set. Runs 2 ticks after the ACK because the client installs its S2C receiver during the
     * join callback.
     */
    private void applyOnJoin(Player player) {
        java.util.List<String> configured = getConfig().getStringList("apply-on-join.pages");
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            int applied = 0;
            for (String name : configured.isEmpty() ? listPageNames() : configured) {
                String trimmed = name == null ? "" : name.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (configured.isEmpty() && !isAmbientPage(trimmed)) {
                    continue;
                }
                sendPage(player, trimmed);
                applied++;
            }
            getLogger().info("Applied " + applied + " join page(s) to " + player.getName());
        }, 2L);
    }

    /** True when the page should be applied on join without being opened by a trigger. */
    private boolean isAmbientPage(String name) {
        java.nio.file.Path file = resolvePageFile(name);
        if (file == null) {
            return false;
        }
        com.cloudcore.api.page.PageLoader.CompiledPage compiled;
        try {
            compiled = com.cloudcore.api.page.PageLoader.load(pagesRoot, file, Map.of());
        } catch (com.cloudcore.api.page.PageLoadException exception) {
            return false;
        }
        UiDocument document = compiled.document();
        Object optOut = document.surfaceOptions().get("on_join");
        if (optOut instanceof Boolean enabled && !enabled) {
            return false;
        }
        return switch (document.surface()) {
            case HUD, PLAYER_INVENTORY, TOOLTIP, BOSS_BAR -> true;
            default -> false;
        };
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

    private static final java.util.List<String> TAB_SUBCOMMANDS = java.util.List.of(
            "accessories", "validate", "reload", "state-export", "state-import",
            "equipment", "equipment-stand", "page", "ping", "probe", "boss", "close",
            "diagnostic", "demo-hud", "demo-hud-rpg", "demo-hud-bg", "demo-tooltip",
            "demo-container", "demo-readonly", "demo-readonly-allow", "demo-passthrough",
            "demo-inventory");

    @Override
    public java.util.List<String> onTabComplete(CommandSender sender, Command command,
            String alias, String[] args) {
        if (args.length == 1) {
            return TAB_SUBCOMMANDS.stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(java.util.Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("state-import")) {
            java.nio.file.Path exports = getDataFolder().toPath().resolve("exports");
            if (java.nio.file.Files.isDirectory(exports)) {
                try (var files = java.nio.file.Files.list(exports)) {
                    return files.map(file -> file.getFileName().toString())
                            .filter(name -> name.startsWith(args[1]))
                            .sorted()
                            .toList();
                } catch (java.io.IOException ignored) {
                    return java.util.List.of();
                }
            }
            return java.util.List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("page") && pagesRoot != null) {
            try (var files = java.nio.file.Files.list(pagesRoot)) {
                return files.map(file -> file.getFileName().toString())
                        .filter(name -> name.endsWith(".yml"))
                        .map(name -> name.substring(0, name.length() - ".yml".length()))
                        .filter(name -> name.startsWith(args[1]))
                        .sorted()
                        .toList();
            } catch (java.io.IOException ignored) {
                return java.util.List.of();
            }
        }
        if ((args.length == 2 && args[0].equalsIgnoreCase("accessories"))
                || (args.length == 3 && args[0].equalsIgnoreCase("page"))) {
            String prefix = args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
            return getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .toList();
        }
        return java.util.List.of();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PING_PERMISSION)) {
            sender.sendMessage(Component.text("No permission."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("validate")) {
            validatePages(sender);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadPages(sender);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("state-export")) {
            exportState(sender, args[1]);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("state-import")) {
            importState(sender, args[1]);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("equipment")) {
            Player target = args.length >= 3
                    ? getServer().getPlayerExact(args[2])
                    : sender instanceof Player self ? self : null;
            if (target == null) {
                sender.sendMessage(Component.text(
                        "Usage: /cloudcore equipment <asset> [player]"));
                return true;
            }
            com.cloudcore.bukkit.item.EquipmentAssets.applyToHand(target, args[1], this);
            sender.sendMessage(Component.text("Cloud Core equipment asset '" + args[1]
                    + "' applied to " + target.getName() + "'s held item"));
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("equipment-stand")) {
            Player target = args.length >= 3
                    ? getServer().getPlayerExact(args[2])
                    : sender instanceof Player self ? self : null;
            if (target == null) {
                sender.sendMessage(Component.text(
                        "Usage: /cloudcore equipment-stand <asset> [player]"));
                return true;
            }
            org.bukkit.inventory.ItemStack chest = new org.bukkit.inventory.ItemStack(
                    org.bukkit.Material.NETHERITE_CHESTPLATE);
            if (!com.cloudcore.bukkit.item.EquipmentAssets.apply(chest, args[1])) {
                sender.sendMessage(Component.text("Cannot apply equipment asset: " + args[1]));
                return true;
            }
            org.bukkit.Location where = target.getLocation().clone()
                    .add(target.getLocation().getDirection().setY(0).normalize().multiply(2.0))
                    .add(0, 0.2, 0);
            org.bukkit.entity.ArmorStand stand = target.getWorld().spawn(where,
                    org.bukkit.entity.ArmorStand.class, entity -> {
                        entity.setArms(true);
                        entity.setBasePlate(false);
                        entity.setGravity(false);
                        entity.setInvulnerable(true);
                        entity.getEquipment().setChestplate(chest);
                    });
            stand.setRotation(target.getLocation().getYaw() + 180.0f, 0.0f);
            sender.sendMessage(Component.text("Cloud Core equipment stand '" + args[1]
                    + "' spawned for " + target.getName()));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("accessories")) {
            Player target = args.length >= 2
                    ? getServer().getPlayerExact(args[1])
                    : sender instanceof Player self ? self : null;
            if (target == null) {
                sender.sendMessage(Component.text("Usage: /cloudcore accessories [player]"));
                return true;
            }
            accessorySlots.open(target);
            if (!target.equals(sender)) {
                sender.sendMessage(Component.text("Cloud Core accessories opened for "
                        + target.getName()));
            }
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("page")) {
            Player target = args.length >= 3
                    ? getServer().getPlayerExact(args[2])
                    : sender instanceof Player self ? self : null;
            if (target == null) {
                sender.sendMessage(Component.text(
                        "Usage: /cloudcore page <name> [player]"));
                return true;
            }
            sendPage(target, args[1]);
            sender.sendMessage(Component.text("Cloud Core page '" + args[1] + "' sent to "
                    + target.getName()));
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
            } else if (args[0].equalsIgnoreCase("demo-hud-rpg")) {
                sendRpgHud(target);
            } else if (args[0].equalsIgnoreCase("demo-hud-bg")) {
                sendHudBackground(target);
            } else if (args[0].equalsIgnoreCase("demo-tooltip")) {
                sendTooltipOpen(target);
            } else if (args[0].equalsIgnoreCase("demo-container")) {
                sendDemoContainer(target);
            } else if (args[0].equalsIgnoreCase("demo-readonly")) {
                openDemoChest(target, ContainerClickPolicy.Interaction.READONLY);
            } else if (args[0].equalsIgnoreCase("demo-readonly-allow")) {
                openDemoChest(target, ContainerClickPolicy.Interaction.READONLY, true);
            } else if (args[0].equalsIgnoreCase("demo-passthrough")) {
                openDemoChest(target, ContainerClickPolicy.Interaction.PASSTHROUGH);
            } else if (args[0].equalsIgnoreCase("demo-inventory")) {
                sendDemoInventory(target);
            } else if (args[0].equalsIgnoreCase("demo-layout")) {
                sendDemoLayout(target);
            } else if (args[0].equalsIgnoreCase("demo-assets")) {
                sendDemoAssets(target);
            } else if (args[0].equalsIgnoreCase("demo-scroll")) {
                sendDemoScroll(target);
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
                    "Usage: /cloudcore <validate|reload|page <name> [player]> or "
                    + "/cloudcore <demo|demo-big|demo-huge|demo-hud|demo-hud-bg"
                    + "|demo-tooltip|demo-container|demo-readonly|demo-passthrough"
                    + "|demo-inventory|demo-layout|demo-assets|demo-scroll"
                    + "|ping|probe|boss|close|diagnostic> [player]"));
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
        if (args.length == 1 && args[0].equalsIgnoreCase("demo-hud-rpg")) {
            sendRpgHud(player);
            sender.sendMessage(Component.text("Cloud Core RPG HUD sent."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("demo-hud-bg")) {
            sendHudBackground(player);
            sender.sendMessage(Component.text("Cloud Core HUD background demo sent."));
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
        if (args.length == 1 && args[0].equalsIgnoreCase("demo-passthrough")) {
            openDemoChest(player, ContainerClickPolicy.Interaction.PASSTHROUGH);
            sender.sendMessage(Component.text("Cloud Core passthrough container demo sent."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("demo-inventory")) {
            sendDemoInventory(player);
            sender.sendMessage(Component.text("Cloud Core inventory demo sent."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("demo-layout")) {
            sendDemoLayout(player);
            sender.sendMessage(Component.text("Cloud Core layout demo sent."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("demo-assets")) {
            sendDemoAssets(player);
            sender.sendMessage(Component.text("Cloud Core asset demo sent."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("demo-scroll")) {
            sendDemoScroll(player);
            sender.sendMessage(Component.text("Cloud Core scroll demo sent."));
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("ping")) {
            sender.sendMessage(Component.text(
                    "Usage: /" + label
                            + " <page <name> [player]|validate|reload"
                            + "|ping|demo|demo-big|demo-huge|demo-scroll|demo-assets"
                            + "|demo-hud|demo-tooltip|demo-container|demo-readonly"
                            + "|demo-inventory|probe|boss|close|diagnostic>"));
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
                || action.equalsIgnoreCase("demo-hud-rpg")
                || action.equalsIgnoreCase("demo-tooltip")
                || action.equalsIgnoreCase("demo-hud-bg")
                || action.equalsIgnoreCase("demo-container")
                || action.equalsIgnoreCase("demo-readonly")
                || action.equalsIgnoreCase("demo-readonly-allow")
                || action.equalsIgnoreCase("demo-passthrough")
                || action.equalsIgnoreCase("demo-inventory")
                || action.equalsIgnoreCase("demo-assets")
                || action.equalsIgnoreCase("demo-layout")
                || action.equalsIgnoreCase("demo-scroll");
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
                  "surface_options": {
                    "player_inventory": { "show_player_model": true,
                                          "show_effects": true }
                  },
                  "elements": [
                    { "id": "inventory_bg", "type": "panel", "x": 0, "y": 0,
                      "width": "fill", "height": "fill",
                      "style": { "background": "#E0203040" } },
                    { "id": "inventory_hint", "type": "label", "x": 60, "y": 6,
                      "text": "Cloud Core", "color": "#FFFFFFFF" },
                    { "id": "inventory_main", "type": "inventory_grid", "x": 0, "y": 0,
                      "width": "fill", "height": 54,
                      "slots": [9, 35], "origin": [12, 84], "cell": [18, 18],
                      "columns": 9 },
                    { "id": "inventory_hotbar", "type": "inventory_grid", "x": 0, "y": 0,
                      "width": "fill", "height": 18,
                      "slots": [36, 44], "origin": [12, 142], "cell": [18, 18],
                      "columns": 9 }
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
        openDemoChest(player, interaction, false);
    }

    private void openDemoChest(Player player, ContainerClickPolicy.Interaction interaction,
            boolean allowPlayerInventory) {
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
                  "container": { "rows": 3, "interaction": "%s",
                                 "allow_player_inventory": %s },
                  "elements": [
                    { "id": "container_bg", "type": "panel", "x": 0, "y": 0,
                      "width": "fill", "height": "fill",
                      "style": { "background": "#E0203040" } },
                    { "id": "container_hint", "type": "label", "x": 8, "y": 6,
                      "text": "Cloud Core Container", "color": "#FFFFFFFF" },
                    { "id": "container_grid", "type": "inventory_grid", "x": 0, "y": 0,
                      "width": "fill", "height": 18,
                      "slots": [0, 8], "origin": [24, 20], "cell": [18, 18],
                      "columns": 9 },
                    { "id": "container_grid_mid", "type": "inventory_grid", "x": 0, "y": 0,
                      "width": "fill", "height": 18,
                      "slots": [9, 17], "origin": [24, 38], "cell": [18, 18],
                      "columns": 9 },
                    { "id": "container_grid_low", "type": "inventory_grid", "x": 0, "y": 0,
                      "width": "fill", "height": 18,
                      "slots": [18, 26], "origin": [24, 58], "cell": [18, 18],
                      "columns": 9 }
                  ]
                }
                """.formatted(interaction.name().toLowerCase(java.util.Locale.ROOT),
                        allowPlayerInventory);
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
                        player.getUniqueId(), uiId, interaction, declared, Map.of(),
                        allowPlayerInventoryOf(definition));
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

    private boolean allowPlayerInventoryOf(UiDefinition definition) {
        try {
            var container = UiDocumentJson.parse(definition.data()).container();
            return container != null && container.allowPlayerInventory();
        } catch (PageLoadException exception) {
            return false;
        }
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

    /** Background-layer HUD: a red band behind the hotbar, proving addFirst ordering. */
    private void sendHudBackground(Player player) {
        String uiId = "cloudcore_demo_hud_bg";
        int token = random.nextInt();
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        session.lastUiId = uiId;
        UiOpen open = new UiOpen(uiId, 1, SurfaceType.HUD, BindMode.PERSISTENT,
                token, RenderMode.SURFACE_NATIVE, 0, "background");
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_OPEN,
                UiCodec.encodeOpen(open, Limits.S2C)));
        String json = """
                {
                  "schema": 1,
                  "id": "cloudcore_demo_hud_bg",
                  "surface": "hud",
                  "title": { "runs": [ { "text": "HUD background" } ] },
                  "size": [200, 40],
                  "surface_options": { "anchor": "bottom_center", "offset_y": -6 },
                  "elements": [
                    { "id": "band", "type": "panel", "x": 0, "y": 0,
                      "width": 200, "height": 40,
                      "style": { "background": "#66FF3040" } }
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
        getLogger().info("Sent HUD background demo to " + player.getName());
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
                  "surface_options": {
                    "max_height": 160,
                    "scrollbar_width": 3,
                    "scrollbar": { "track_color": "#60000000", "thumb_color": "#FF9AD0FF" }
                  },
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
                  "surface_options": { "anchor": "top_center", "offset_y": 20 },
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

    /**
     * A medieval RPG HUD that replaces the vanilla hearts, hunger, experience bar and hotbar.
     *
     * <p>{@code replace} makes the client cancel those vanilla draws; {@code value_from} /
     * {@code text_from} read live player values each frame, so no document update is needed when
     * health or hunger changes. The textures are the generated CloudCore UI set under
     * {@code resourcepacks/CloudCore/ui/} ({@code local:} paths).
     */
    private void sendRpgHud(Player player) {
        String uiId = "cloudcore_hud_rpg";
        int token = random.nextInt();
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        session.lastUiId = uiId;
        UiOpen open = new UiOpen(uiId, 1, SurfaceType.HUD, BindMode.PERSISTENT,
                token, RenderMode.SURFACE_NATIVE, 0, "foreground");
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_OPEN,
                UiCodec.encodeOpen(open, Limits.S2C)));
        String json = """
                {
                  "schema": 1,
                  "id": "cloudcore_hud_rpg",
                  "surface": "hud",
                  "title": { "runs": [ { "text": "RPG HUD" } ] },
                  "size": [200, 80],
                  "fonts": {
                    "alibaba": { "provider": "nanovg_ttf", "source": "local",
                                 "id": "公共/字体/AlibabaBold.ttf" }
                  },
                  "surface_options": {
                    "anchor": "bottom_center",
                    "offset_y": -4,
                    "replace": ["health", "food", "xp", "hotbar"]
                  },
                  "elements": [
                    { "id": "panel", "type": "panel", "x": 0, "y": 0,
                      "width": 200, "height": 80,
                      "style": { "background": "local:ui/hud_panel.png",
                                 "nine_slice": [6, 6, 6, 6] } },

                    { "id": "hp_key", "type": "label", "x": 12, "y": 9,
                      "text": "生命", "font": "alibaba", "color": "#FFE8C98A" },
                    { "id": "hp_text", "type": "label", "x": 36, "y": 9,
                      "text": "", "text_from": "health", "font": "alibaba",
                      "color": "#FFFFEDD0" },
                    { "id": "hp_bar", "type": "progress", "x": 12, "y": 20,
                      "width": 82, "height": 8,
                      "value_from": "health",
                      "texture_fill": "local:ui/hp_fill.png",
                      "style": { "background": "#FF140C0C" } },

                    { "id": "food_key", "type": "label", "x": 106, "y": 9,
                      "text": "饱食", "font": "alibaba", "color": "#FFE8C98A" },
                    { "id": "food_text", "type": "label", "x": 130, "y": 9,
                      "text": "", "text_from": "food", "font": "alibaba",
                      "color": "#FFFFEDD0" },
                    { "id": "food_bar", "type": "progress", "x": 106, "y": 20,
                      "width": 82, "height": 8,
                      "value_from": "food",
                      "texture_fill": "local:ui/food_fill.png",
                      "style": { "background": "#FF140C0C" } },

                    { "id": "xp_bar", "type": "progress", "x": 12, "y": 32,
                      "width": 176, "height": 5,
                      "value_from": "xp",
                      "texture_fill": "local:ui/xp_fill.png",
                      "style": { "background": "#FF0A1218" } },
                    { "id": "lvl_key", "type": "label", "x": 12, "y": 40,
                      "text": "等级", "font": "alibaba", "color": "#FFE8C98A" },
                    { "id": "lvl_value", "type": "label", "x": 36, "y": 40,
                      "text": "", "text_from": "level", "font": "alibaba",
                      "color": "#FFFFEDD0" },
                    { "id": "xp_text", "type": "label", "x": 152, "y": 40,
                      "text": "", "text_from": "xp", "font": "alibaba",
                      "color": "#FF9FD3D8" },

                    { "id": "hotbar", "type": "hotbar", "x": 19, "y": 54,
                      "slots": 9, "cell": 18, "gap": 0, "show_counts": true,
                      "style": { "slot": "#C0181C22",
                                 "slot_border": "#FF8E7434",
                                 "selected": "#FFFFD479" } }
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
        getLogger().info("Sent RPG HUD to " + player.getName());
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
        String uiId = session.lastUiId;
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_CLOSE,
                UiCodec.encodeClose(new UiClose(uiId, 0), Limits.S2C)));
        getLogger().info("Sent UI_CLOSE to " + player.getName() + " ui=" + uiId);
        session.lastUiId = null;
        session.refreshes.remove(uiId);
        surfaceProviders.close(player.getUniqueId(), uiId);
        addonEvents.publish(new com.cloudcore.api.event.SessionCloseEvent(player.getUniqueId(),
                uiId, "server"));
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
                  "surface_options": {
                    "boss_bar": {
                      "bar": {
                        "width": 200,
                        "height": 8,
                        "border": "#FF000000",
                        "background": "#FF1A2028",
                        "fill": "local:test/red.png",
                        "tint_from_event": false
                      }
                    }
                  },
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
        // Partial progress so the texture fill is visibly clipped at 55%.
        bar.setProgress(0.55);
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
        Player player = event.getWhoClicked() instanceof Player p ? p : null;

        // Custom slot pages put a rule on individual inventory indices. Rules only gate
        // placing an item into the slot; taking an item out is always allowed by MANAGED.
        if (!top && event.getClick().isShiftClick() && !holder.guards().isEmpty()) {
            // Bukkit does not expose the destination slot of a shift move, so a guarded page
            // fails closed instead of guessing and possibly bypassing the item rule.
            event.setCancelled(true);
            logDeniedSlot(holder, rawSlot, player, "shift-from-player");
            return;
        }
        if (top && holder.guarded(rawSlot)) {
            ItemStack incoming = incomingItem(event, player);
            if (incoming != null && !holder.canPlace(player, rawSlot, incoming)) {
                event.setCancelled(true);
                logDeniedSlot(holder, rawSlot, player, "rule");
                return;
            }
        }
        if (ContainerClickPolicy.allow(holder.interaction(), top, rawSlot,
                holder.declaredSlots(), holder.allowPlayerInventory())) {
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
        Player player = event.getWhoClicked() instanceof Player p ? p : null;
        ItemStack dragged = event.getOldCursor();
        if (dragged != null && !dragged.getType().isAir()) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot >= 0 && rawSlot < topSize && holder.guarded(rawSlot)
                        && !holder.canPlace(player, rawSlot, dragged)) {
                    event.setCancelled(true);
                    logDeniedSlot(holder, rawSlot, player, "drag");
                    return;
                }
            }
        }
        for (int rawSlot : event.getRawSlots()) {
            boolean top = rawSlot >= 0 && rawSlot < topSize;
            if (!ContainerClickPolicy.allow(holder.interaction(), top, rawSlot,
                    holder.declaredSlots(), holder.allowPlayerInventory())) {
                event.setCancelled(true);
                getLogger().info("CANCELLED drag ui=" + holder.uiId()
                        + " interaction=" + holder.interaction()
                        + " slots=" + event.getRawSlots()
                        + " player=" + event.getWhoClicked().getName());
                return;
            }
        }
    }

    /** The stack a click would put into a guarded slot, or null for a removal. */
    private static ItemStack incomingItem(InventoryClickEvent event, Player player) {
        if (event.getClick().isKeyboardClick() && player != null) {
            if (event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND) {
                return player.getInventory().getItemInOffHand();
            }
            int hotbar = event.getHotbarButton();
            if (hotbar >= 0 && hotbar < 9) {
                return player.getInventory().getItem(hotbar);
            }
        }
        ItemStack cursor = event.getCursor();
        return cursor == null || cursor.getType().isAir() ? null : cursor;
    }

    private void logDeniedSlot(CloudInventoryHolder holder, int slot, Player player, String why) {
        getLogger().info("DENIED slot ui=" + holder.uiId() + " slot=" + slot
                + " reason=" + why + " player="
                + (player == null ? "<unknown>" : player.getName()));
    }

    /** Flush custom-slot contents before Bukkit drops the inventory. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCloudInventoryClose(InventoryCloseEvent event) {
        CloudInventoryHolder holder = holderOf(event.getInventory());
        if (holder == null || accessorySlots == null || !accessorySlots.owns(holder)) {
            return;
        }
        if (event.getPlayer() instanceof Player player) {
            accessorySlots.save(player, event.getInventory());
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

    /**
     * Demonstrates {@code scroll}: children are clipped to the box and offset by
     * {@code scroll_y}. Rows outside the box must be cut in both render backends.
     */
    /**
     * Loads an authored YAML page, expands it against an empty state snapshot and sends the
     * compiled JSON to the client. Only custom screens are bound here; other surfaces keep
     * their dedicated flows (container/HUD/tooltip/boss).
     */
    private void sendPage(Player player, String name) {
        if (pagesRoot == null) {
            getLogger().warning("Pages directory unavailable; cannot send page " + name);
            return;
        }
        java.nio.file.Path pageFile = resolvePageFile(name);
        if (pageFile == null) {
            getLogger().warning("Page not found: " + name + " under " + pagesRoot);
            return;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        Map<String, Object> state = session.pageState.computeIfAbsent(name,
                ignored -> new ConcurrentHashMap<>());
        com.cloudcore.api.page.PageLoader.CompiledPage compiled;
        try {
            compiled = com.cloudcore.api.page.PageLoader.load(pagesRoot, pageFile, state);
        } catch (com.cloudcore.api.page.PageLoadException exception) {
            getLogger().warning("Page '" + name + "' failed to load: " + exception.getMessage());
            return;
        }
        UiDocument document = compiled.document();
        putCompiledPage(player.getUniqueId(), compiled);
        session.pageNames.put(document.id(), name);
        sendCompiledPage(player, session, name, compiled);
    }

    /**
     * Sends {@code UI_OPEN} plus the document for an already compiled page. Used by the YAML
     * path (where {@code name} is the file name) and by code-built pages from the addon API.
     * The document is resent on the next tick so the client has installed its play handler.
     */
    private void sendCompiledPage(Player player, Session session, String surfaceKey,
            com.cloudcore.api.page.PageLoader.CompiledPage compiled) {
        UiDocument document = compiled.document();
        SurfaceType surface = document.surface();
        if (surface == SurfaceType.UNKNOWN) {
            getLogger().warning("Page '" + document.id() + "' declares an unknown surface");
            return;
        }
        document = expandPlaceholders(player, document);
        // The protocol requires UI_OPEN.uiId and document.id to match.
        String uiId = document.id();
        int token = random.nextInt();
        if (session.lastUiId != null && !session.lastUiId.equals(uiId)) {
            // The client replaces the previous screen; stop refreshing a page nobody sees.
            session.refreshes.remove(session.lastUiId);
        }
        session.lastUiId = uiId;
        session.sentDocuments.put(uiId, document);
        com.cloudcore.api.page.RefreshPlan plan = compiled.refresh();
        if ((plan.hasPeriodic() || plan.hasEvent()) && supportsIncremental(surface)) {
            session.refreshes.put(uiId, new PageRefresh(plan));
            if (plan.async()) {
                getLogger().info("Page '" + uiId + "' refreshes placeholders asynchronously; "
                        + "PlaceholderAPI expansions must be thread-safe");
            }
        } else if (plan.hasPeriodic() || plan.hasEvent()) {
            getLogger().warning("Page '" + uiId + "' requests placeholder refresh on surface "
                    + surface + "; only custom_screen and hud are supported");
            session.refreshes.remove(uiId);
        } else {
            session.refreshes.remove(uiId);
        }
        int revision = session.pageRevisions.merge(uiId, 1, Integer::sum);
        UiOpen open = openForSurface(surface, uiId, revision, token, surfaceKey, document);
        // An addon provider runs before the wire: it may bind its own backing state and
        // decide the correlation token. Declining leaves the client untouched.
        com.cloudcore.api.surface.SurfaceProvider provider =
                surfaceProviders.find(surface).orElse(null);
        com.cloudcore.api.surface.SurfaceSession providerSession = null;
        if (provider != null) {
            java.util.Optional<com.cloudcore.api.surface.SurfaceSession> opened;
            try {
                opened = provider.open(player.getUniqueId(), uiId, new com.cloudcore.api.OpenOptions(
                        open.surface(), open.bindMode(), open.renderMode(), document.theme(),
                        true));
            } catch (RuntimeException exception) {
                getLogger().log(Level.WARNING, "Surface provider " + surface
                        + " failed for page '" + uiId + "'", exception);
                return;
            }
            if (opened.isEmpty()) {
                getLogger().warning("Surface provider " + surface + " declined page '" + uiId
                        + "' for " + player.getName());
                return;
            }
            providerSession = opened.get();
            if (!uiId.equals(providerSession.uiId())) {
                getLogger().warning("Surface provider " + surface + " returned uiId '"
                        + providerSession.uiId() + "' for page '" + uiId + "'; ignoring it");
                return;
            }
            open = new UiOpen(uiId, revision, open.surface(), open.surfaceId(),
                    open.bindMode(), providerSession.token(), open.renderMode(), open.flags(),
                    open.surfaceKey());
        }
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_OPEN,
                UiCodec.encodeOpen(open, Limits.S2C)));
        addonEvents.publish(new com.cloudcore.api.event.SessionOpenEvent(player.getUniqueId(),
                uiId, surface, document.id(), revision));
        sendTextureRefs(player, compiled);
        String json = com.cloudcore.api.document.UiDocumentJson.write(document);
        UiDefinition definition = new UiDefinition(uiId, revision, 0,
                json.getBytes(StandardCharsets.UTF_8));
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION,
                        UiCodec.encodeDefinition(definition, Limits.S2C)));
            }
        }, 1L);
        if (providerSession != null) {
            try {
                provider.apply(providerSession);
            } catch (RuntimeException exception) {
                getLogger().log(Level.WARNING, "Surface provider " + surface
                        + " apply failed for page '" + uiId + "'", exception);
            }
        }
        if (surface == SurfaceType.BOSS_BAR) {
            createDemoBossBars(player, session, document);
        }
        getLogger().info("Sent page '" + surfaceKey + "' to " + player.getName()
                + " elements=" + document.elements().size() + " bytes=" + json.length());
    }

    /**
     * Creates the single multi-layer sentinel boss bar described by a {@code boss_bar} page's
     * {@code surface_options.boss_bar.demo_layers} list.
     *
     * <p>The stack is <em>one</em> Paper boss bar, not one per layer. Its title carries every
     * layer's remaining strength at once:
     * {@code \0CCB:<token>:<p1>,<p2>,...\0<name>} with each {@code p} a per-mille value.
     * The client paints the layers over each other, so burning the outermost one down
     * uncovers the layer that was already underneath instead of switching to a new bar.
     *
     * <p>Production plugins create their own bar with the same marker and write their layers'
     * values into it; the demo below just drains the stack on a timer so the effect is
     * visible without a boss behind it.
     */
    private void createDemoBossBars(Player player, Session session, UiDocument document) {
        Object bossBar = document.surfaceOptions().get("boss_bar");
        if (!(bossBar instanceof Map<?, ?> options)) {
            return;
        }
        Object rawLayers = options.get("demo_layers");
        if (!(rawLayers instanceof java.util.List<?> specs) || specs.isEmpty()) {
            return;
        }
        java.util.List<DemoLayer> layers = new java.util.ArrayList<>();
        for (Object entry : specs) {
            if (!(entry instanceof Map<?, ?> spec)) {
                continue;
            }
            String name = spec.get("name") instanceof String text && !text.isBlank()
                    ? text : "Boss";
            double progress = spec.get("progress") instanceof Number number
                    ? Math.max(0.0, Math.min(1.0, number.doubleValue())) : 1.0;
            layers.add(new DemoLayer(name, progress, parseBarColor(spec.get("color"))));
        }
        if (layers.isEmpty()) {
            return;
        }
        removeSessionBossBars(session);

        String bossName = options.get("demo_name") instanceof String text && !text.isBlank()
                ? text : layers.get(0).name();
        session.layerToken = Integer.toHexString(random.nextInt());
        session.demoLayers = layers;
        session.layerValues = new double[layers.size()];
        for (int index = 0; index < layers.size(); index++) {
            session.layerValues[index] = layers.get(index).progress();
        }
        session.layerTitle = null;

        BossBar bar = Bukkit.createBossBar(
                layerTitle(session, bossName), layers.get(0).color(), BarStyle.SOLID);
        bar.addPlayer(player);
        bar.setVisible(true);
        bar.setProgress(outerValue(session));
        session.layeredBar = bar;
        session.layerBossName = bossName;
        session.layerTitle = bar.getTitle();

        int seconds = options.get("demo_duration") instanceof Number number
                ? Math.max(1, number.intValue()) : 30;
        // Each layer gets an equal slice of the run so the whole stack drains in
        // demo_duration, whatever the layer count.
        double secondsPerLayer = Math.max(0.5, (double) seconds / layers.size());
        long period = 2L;
        double stepPerTick = 1.0 / (secondsPerLayer * 20.0);
        session.layerTask = getServer().getScheduler().runTaskTimer(this,
                () -> advanceDemoLayer(session, stepPerTick * period), period, period);
        getLogger().info("Created layered demo boss bar for " + player.getName()
                + " layers=" + layers.size());
    }

    /** Remaining strength of the outermost layer that still has any. */
    private static double outerValue(Session session) {
        if (session.layerValues == null) {
            return 0.0;
        }
        for (double value : session.layerValues) {
            if (value > 0.0) {
                return Math.min(1.0, value);
            }
        }
        return 0.0;
    }

    /** Sentinel header carrying every layer's remaining strength, in per-mille. */
    private static String layerTitle(Session session, String bossName) {
        StringBuilder encoded = new StringBuilder();
        for (int index = 0; index < session.layerValues.length; index++) {
            if (index > 0) {
                encoded.append(',');
            }
            encoded.append(Math.max(0, Math.min(1000,
                    (int) Math.round(session.layerValues[index] * 1000))));
        }
        return "\u0000CCB:" + session.layerToken + ":" + encoded + "\u0000" + bossName;
    }

    /**
     * Drains the outermost layer that still has strength. The bar stays up until every layer
     * is empty, so the stack never flickers out between phases.
     */
    private void advanceDemoLayer(Session session, double amount) {
        BossBar bar = session.layeredBar;
        if (bar == null || session.layerValues == null) {
            return;
        }
        for (int index = 0; index < session.layerValues.length; index++) {
            if (session.layerValues[index] <= 0.0) {
                continue;
            }
            session.layerValues[index] = Math.max(0.0, session.layerValues[index] - amount);
            break;
        }
        if (outerValue(session) <= 0.0) {
            bar.setProgress(0.0);
            removeSessionBossBars(session);
            return;
        }
        bar.setProgress(outerValue(session));
        // Only push a new title when the per-mille encoding actually changed, so the stack
        // does not cost a title packet every tick.
        String title = layerTitle(session, session.layerBossName);
        if (!title.equals(session.layerTitle)) {
            session.layerTitle = title;
            bar.setTitle(title);
            int outermost = 0;
            while (outermost < session.layerValues.length - 1
                    && session.layerValues[outermost] <= 0.0) {
                outermost++;
            }
            bar.setColor(session.demoLayers.get(outermost).color());
        }
    }

    /** One entry of a page's {@code demo_layers} list. */
    private record DemoLayer(String name, double progress, BarColor color) {
    }

    private static BarColor parseBarColor(Object value) {
        if (value instanceof String text) {
            try {
                return BarColor.valueOf(text.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall through to the default rather than failing the page.
            }
        }
        return BarColor.RED;
    }

    private static void removeSessionBossBars(Session session) {
        if (session.layerTask != null) {
            session.layerTask.cancel();
            session.layerTask = null;
        }
        if (session.layeredBar != null) {
            session.layeredBar.removeAll();
            session.layeredBar = null;
        }
        session.demoLayers = null;
        session.layerBossName = null;
        for (BossBar bar : session.bossBars) {
            bar.removeAll();
        }
        session.bossBars.clear();
        if (session.bossBar != null) {
            session.bossBar.removeAll();
            session.bossBar = null;
        }
    }

    /**
     * Maps a document's declared surface to the {@code UI_OPEN} parameters that surface needs.
     * This is the single place that knows how each surface binds, so any page — YAML or
     * addon-built — lands on its surface without per-call special cases.
     *
     * <p>Persistent surfaces (HUD, tooltip, boss bar, player inventory) live until UI_CLOSE;
     * screens and containers are bound to the next open of that kind.
     */
    private static UiOpen openForSurface(SurfaceType surface, String uiId, int revision,
            int token, String surfaceKey, UiDocument document) {
        return switch (surface) {
            case HUD -> new UiOpen(uiId, revision, surface, BindMode.PERSISTENT, token,
                    RenderMode.SURFACE_NATIVE, 0, hudLayerOf(document));
            case TOOLTIP -> new UiOpen(uiId, revision, surface, BindMode.PERSISTENT, token,
                    RenderMode.SURFACE_NATIVE, 0, surfaceKey == null ? "default" : surfaceKey);
            // The inventory skin is drawn by CloudInventoryScreen, which replaces the vanilla
            // InventoryScreen, so it binds through the screen path rather than the native one.
            case PLAYER_INVENTORY -> new UiOpen(uiId, revision, surface, BindMode.PERSISTENT,
                    token, RenderMode.SCREEN, 0,
                    surfaceKey == null ? "default" : surfaceKey);
            case BOSS_BAR -> new UiOpen(uiId, revision, surface, BindMode.PERSISTENT, token,
                    RenderMode.SURFACE_NATIVE, 0,
                    surfaceKey == null ? Integer.toHexString(token) : surfaceKey);
            case CONTAINER -> new UiOpen(uiId, revision, surface, BindMode.NEXT_CONTAINER, token,
                    RenderMode.SCREEN, 0, surfaceKey);
            case CUSTOM_SCREEN, UNKNOWN -> new UiOpen(uiId, revision, SurfaceType.CUSTOM_SCREEN,
                    BindMode.NONE, token, RenderMode.SCREEN, 0, surfaceKey);
        };
    }

    /** Layer name for a HUD page: {@code surface_options.layer}, defaulting to foreground. */
    private static String hudLayerOf(UiDocument document) {
        Object value = document.surfaceOptions().get("layer");
        if (value instanceof String text) {
            String normalised = text.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalised.equals("background") || normalised.equals("foreground")
                    || normalised.equals("overlay")) {
                return normalised;
            }
        }
        return "foreground";
    }

    private TextureProxy createTextureProxy() {
        String mode = getConfig().getString("textures.url.mode", "client");
        java.util.List<String> allowlist = getConfig().getStringList(
                "textures.url.allowlist");
        long maxBytes = getConfig().getLong("textures.url.max-bytes", 8L * 1024L * 1024L);
        int timeoutSeconds = Math.max(1,
                getConfig().getInt("textures.url.timeout-seconds", 10));
        int ttlSeconds = Math.max(0,
                getConfig().getInt("textures.url.ttl-seconds", 3600));
        long cacheMaxBytes = Math.max(1,
                getConfig().getLong("textures.url.cache-mb", 256L)) * 1024L * 1024L;
        TextureProxy.Mode parsed = TextureProxy.Mode.parse(mode);
        if (parsed != TextureProxy.Mode.CLIENT && allowlist.isEmpty()) {
            getLogger().warning("textures.url.mode=" + parsed
                    + " but textures.url.allowlist is empty; proxy requests will be rejected");
        }
        return new TextureProxy(new TextureProxy.Settings(parsed, allowlist, maxBytes,
                java.time.Duration.ofSeconds(timeoutSeconds), ttlSeconds,
                getDataFolder().toPath().resolve("texture-cache"), cacheMaxBytes), getLogger());
    }

    /**
     * A client could ask for any key; only URLs that belong to a page compiled for that
     * player are eligible. The hash key makes collisions irrelevant in practice.
     */
    private String remoteTextureUrl(Player player, String key) {
        Map<String, com.cloudcore.api.page.PageLoader.CompiledPage> pages =
                compiledPages.get(player.getUniqueId());
        if (pages == null) {
            return null;
        }
        for (com.cloudcore.api.page.PageLoader.CompiledPage page : pages.values()) {
            for (com.cloudcore.api.page.PageLoader.RemoteTexture texture : page.textures()) {
                if (texture.key().equals(key)) {
                    return texture.url();
                }
            }
        }
        return null;
    }

    private void handleTextureRequest(Player player, Session session, Frame frame) {
        if (session.hello == null || textureProxy == null || !textureProxy.acceptsRequests()) {
            return;
        }
        com.cloudcore.protocol.TextureRequest request;
        try {
            request = com.cloudcore.protocol.TextureRequestCodec.decode(frame.body(),
                    Limits.C2S);
        } catch (ProtocolException exception) {
            getLogger().warning("Malformed TEXTURE_REQUEST from " + player.getName() + ": "
                    + exception.getMessage());
            return;
        }
        String url = remoteTextureUrl(player, request.key());
        if (url == null) {
            getLogger().warning("TEXTURE_REQUEST for unknown key " + request.key() + " from "
                    + player.getName());
            return;
        }
        textureProxy.request(player.getUniqueId(), request.key(), url,
                put -> sendTexturePut(player, put));
    }

    private void sendTexturePut(Player player, com.cloudcore.protocol.TexturePut put) {
        if (!isEnabled() || player == null || !player.isOnline()) {
            return;
        }
        getServer().getScheduler().runTask(this, () -> {
            if (!isEnabled() || !player.isOnline()) {
                return;
            }
            sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.TEXTURE_PUT,
                    com.cloudcore.protocol.TexturePutCodec.encode(put, Limits.S2C)));
        });
    }

    /** Tells the client which URL images this page uses, or proxies their bytes. */
    private void sendTextureRefs(Player player,
            com.cloudcore.api.page.PageLoader.CompiledPage compiled) {
        if (textureProxy == null) {
            return;
        }
        if (textureProxy.proxyOnOpen()) {
            textureProxy.provideForPage(player.getUniqueId(), compiled.textures(),
                    put -> sendTexturePut(player, put));
            return;
        }
        for (com.cloudcore.api.page.PageLoader.RemoteTexture texture : compiled.textures()) {
            if (!texture.url().startsWith("https://")) {
                getLogger().warning("Page " + compiled.document().id()
                        + " references a non-https texture: " + texture.url());
                continue;
            }
            com.cloudcore.protocol.TextureRef ref = new com.cloudcore.protocol.TextureRef(
                    texture.key(), texture.url(), "", 0, 0, 0, 8L * 1024L * 1024L, 3600);
            sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.TEXTURE_REF,
                    com.cloudcore.protocol.TextureRefCodec.encode(ref, Limits.S2C)));
        }
    }

    /** Compiles every page under the pages root and reports the first error per page. */
    private void validatePages(CommandSender sender) {
        java.util.List<String> names = listPageNames();
        if (names.isEmpty()) {
            sender.sendMessage(Component.text("No pages found under " + pagesRoot));
            return;
        }
        int ok = 0;
        int warnings = 0;
        for (String name : names) {
            java.nio.file.Path file = resolvePageFile(name);
            com.cloudcore.api.page.PageLoader.CompiledPage compiled;
            try {
                compiled = com.cloudcore.api.page.PageLoader.load(pagesRoot, file, Map.of());
            } catch (com.cloudcore.api.page.PageLoadException exception) {
                sender.sendMessage(Component.text("[FAIL] " + name + ": "
                        + exception.getMessage()));
                continue;
            }
            ok++;
            // Attribute-level issues are warnings on purpose; show them here so an author sees
            // that, say, a progress direction will fall back instead of doing nothing visible.
            java.util.List<com.cloudcore.api.document.ValidationIssue> issues =
                    com.cloudcore.api.document.UiDocumentValidator
                            .validate(compiled.document()).issues();
            warnings += issues.size();
            sender.sendMessage(Component.text("[OK] " + name
                    + (issues.isEmpty() ? "" : " (" + issues.size() + " warning(s))")));
            for (com.cloudcore.api.document.ValidationIssue issue : issues) {
                sender.sendMessage(Component.text("  " + issue.severity() + " " + issue.code()
                        + " " + issue.path() + ": " + issue.message()));
            }
        }
        sender.sendMessage(Component.text("Validated " + names.size() + " pages, " + ok + " ok, "
                + warnings + " warning(s)"));
        getLogger().info(sender.getName() + " validated " + names.size() + " pages, " + ok
                + " ok, " + warnings + " warning(s)");
    }

    /** Reloads config.yml, then recompiles every page and refreshes open views. */
    private void reloadPages(CommandSender sender) {
        reloadConfig();
        getLogger().info("Reloaded config.yml");
        java.util.List<String> names = listPageNames();
        int refreshed = 0;
        for (String name : names) {
            java.nio.file.Path file = resolvePageFile(name);
            com.cloudcore.api.page.PageLoader.CompiledPage compiled;
            try {
                compiled = com.cloudcore.api.page.PageLoader.load(pagesRoot, file, Map.of());
            } catch (com.cloudcore.api.page.PageLoadException exception) {
                getLogger().warning("Reload skipped '" + name + "': " + exception.getMessage());
                continue;
            }
            String uiId = compiled.document().id();
            for (Player player : getServer().getOnlinePlayers()) {
                Session session = sessions.get(player.getUniqueId());
                if (session != null && name.equals(session.pageNames.get(uiId))) {
                    // refreshPage recompiles with this player's state and stores that copy.
                    refreshPage(player, session, compiled);
                    refreshed++;
                }
            }
        }
        sender.sendMessage(Component.text("Reloaded " + names.size() + " pages, refreshed "
                + refreshed + " open views"));
        getLogger().info(sender.getName() + " reloaded " + names.size() + " pages");
    }

    /** Page names: {@code <dir>/page.yml}, top-level {@code *.yml}, sorted for stable output. */
    private java.util.List<String> listPageNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (pagesRoot == null) {
            return names;
        }
        try (var stream = java.nio.file.Files.walk(pagesRoot, 4)) {
            stream.filter(java.nio.file.Files::isRegularFile).forEach(file -> {
                String relative = pagesRoot.relativize(file).toString()
                        .replace('\\', '/');
                if (relative.equals("page.yml") || relative.equals("page.yaml")) {
                    names.add("");
                } else if (relative.endsWith("/page.yml")) {
                    names.add(relative.substring(0, relative.length() - "/page.yml".length()));
                } else if (relative.endsWith("/page.yaml")) {
                    names.add(relative.substring(0, relative.length() - "/page.yaml".length()));
                } else if (!relative.contains("/")
                        && (relative.endsWith(".yml") || relative.endsWith(".yaml"))) {
                    names.add(relative.substring(0, relative.lastIndexOf('.')));
                }
            });
        } catch (java.io.IOException exception) {
            getLogger().log(Level.WARNING, "Cannot walk pages directory", exception);
        }
        names.removeIf(String::isEmpty);
        java.util.Collections.sort(names);
        return names;
    }

    private java.nio.file.Path resolvePageFile(String name) {
        for (String candidate : new String[]{
                name + "/page.yml", name + "/page.yaml", name + ".yml", name + ".yaml"}) {
            java.nio.file.Path file = pagesRoot.resolve(candidate).normalize();
            if (!file.startsWith(pagesRoot)) {
                continue;
            }
            if (java.nio.file.Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    private void sendDemoScroll(Player player) {
        String uiId = "cloudcore_demo_scroll";
        int token = random.nextInt();
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        session.lastUiId = uiId;
        UiOpen open = new UiOpen(uiId, 1, SurfaceType.CUSTOM_SCREEN,
                BindMode.NONE, token, RenderMode.SCREEN, 0, "demo-scroll");
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_OPEN,
                UiCodec.encodeOpen(open, Limits.S2C)));
        String json = """
                {
                  "schema": 1,
                  "id": "cloudcore_demo_scroll",
                  "surface": "custom_screen",
                  "title": { "runs": [ { "text": "Cloud Core Scroll" } ] },
                  "size": [320, 180],
                  "elements": [
                    { "id": "root", "type": "panel", "x": 0, "y": 0,
                      "width": "fill", "height": "fill",
                      "style": { "background": "#CC202830" } },
                    { "id": "title", "type": "label", "x": 16, "y": 12,
                      "text": "scroll clip + offset", "color": "#FFFFFFFF" },
                    { "id": "scroller", "type": "scroll", "x": 16, "y": 36,
                      "width": 200, "height": 80, "scroll_y": 26,
                      "style": { "background": "#66101820" },
                      "children": [
                        { "id": "row0", "type": "panel", "x": 0, "y": 0,
                          "width": 200, "height": 18,
                          "style": { "background": "#FF3C6E9F" } },
                        { "id": "row1", "type": "panel", "x": 0, "y": 20,
                          "width": 200, "height": 18,
                          "style": { "background": "#FF4A9F6E" } },
                        { "id": "row2", "type": "panel", "x": 0, "y": 40,
                          "width": 200, "height": 18,
                          "style": { "background": "#FF9F6E3C" } },
                        { "id": "row3", "type": "panel", "x": 0, "y": 60,
                          "width": 200, "height": 18,
                          "style": { "background": "#FF9F3C6E" } },
                        { "id": "row4", "type": "panel", "x": 0, "y": 80,
                          "width": 200, "height": 18,
                          "style": { "background": "#FF6E3C9F" } },
                        { "id": "row5", "type": "panel", "x": 0, "y": 100,
                          "width": 200, "height": 18,
                          "style": { "background": "#FF3C9F9F" } }
                      ] },
                    { "id": "outside", "type": "label", "x": 240, "y": 40,
                      "text": "outside clip", "color": "#FFFF8080" }
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
        getLogger().info("Sent scroll demo UI_OPEN/UI_DEFINITION to " + player.getName());
    }

    /** Row/column/grid layout demo: children are placed by their own sizes plus gap. */
    private void sendDemoLayout(Player player) {
        String uiId = "cloudcore_demo_layout";
        int token = random.nextInt();
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        session.lastUiId = uiId;
        UiOpen open = new UiOpen(uiId, 1, SurfaceType.CUSTOM_SCREEN, BindMode.NONE, token,
                RenderMode.SCREEN, 0, "demo-layout");
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_OPEN,
                UiCodec.encodeOpen(open, Limits.S2C)));
        String json = """
                {
                  "schema": 1,
                  "id": "cloudcore_demo_layout",
                  "surface": "custom_screen",
                  "title": { "runs": [ { "text": "Cloud Core Layout" } ] },
                  "size": [320, 200],
                  "elements": [
                    { "id": "root", "type": "panel", "x": 0, "y": 0,
                      "width": "fill", "height": "fill",
                      "style": { "background": "#CC202830" } },
                    { "id": "title", "type": "label", "x": 16, "y": 12,
                      "text": "row / column / grid", "color": "#FFFFFFFF" },
                    { "id": "row", "type": "row", "x": 16, "y": 36, "gap": 6,
                      "width": 200, "height": 20,
                      "children": [
                        { "id": "r1", "type": "panel", "x": 0, "y": 0,
                          "width": 40, "height": 20,
                          "style": { "background": "#FF3C6E9F" } },
                        { "id": "r2", "type": "panel", "x": 0, "y": 0,
                          "width": 40, "height": 20,
                          "style": { "background": "#FF4A9F6E" } },
                        { "id": "r3", "type": "panel", "x": 0, "y": 0,
                          "width": 40, "height": 20,
                          "style": { "background": "#FF9F6E3C" } }
                      ] },
                    { "id": "column", "type": "column", "x": 16, "y": 70, "gap": 4,
                      "width": 120, "height": 60,
                      "children": [
                        { "id": "c1", "type": "panel", "x": 0, "y": 0,
                          "width": 120, "height": 16,
                          "style": { "background": "#FF9F3C6E" } },
                        { "id": "c2", "type": "panel", "x": 0, "y": 0,
                          "width": 80, "height": 16,
                          "style": { "background": "#FF6E3C9F" } }
                      ] },
                    { "id": "grid", "type": "grid", "x": 200, "y": 70,
                      "columns": 2, "cell": [28, 28], "gap": 4,
                      "width": 100, "height": 100,
                      "children": [
                        { "id": "g1", "type": "panel", "x": 0, "y": 0,
                          "width": 24, "height": 24,
                          "style": { "background": "#FF3C9F9F" } },
                        { "id": "g2", "type": "panel", "x": 0, "y": 0,
                          "width": 24, "height": 24,
                          "style": { "background": "#FF9F9F3C" } },
                        { "id": "g3", "type": "panel", "x": 0, "y": 0,
                          "width": 24, "height": 24,
                          "style": { "background": "#FF7F3C9F" } },
                        { "id": "g4", "type": "panel", "x": 0, "y": 0,
                          "width": 24, "height": 24,
                          "style": { "background": "#FF3C3C9F" } }
                      ] }
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
        getLogger().info("Sent layout demo to " + player.getName());
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
                  "fonts": {
                    "body": { "provider": "nanovg_ttf", "source": "local",
                              "id": "公共/字体/body.ttf" }
                  },
                  "elements": [
                    { "id": "root", "type": "panel", "x": 0, "y": 0,
                      "width": "fill", "height": "fill",
                      "style": { "background": "#CC202830" } },
                    { "id": "title", "type": "label", "x": 16, "y": 14,
                      "text": "item_icon / pack / builtin", "color": "#FFFFFFFF" },
                    { "id": "anim_gif", "type": "image", "x": 240, "y": 30,
                      "width": 32, "height": 32,
                      "texture": "local:test/anim.gif" },
                    { "id": "rich_text", "type": "label", "x": 16, "y": 156,
                      "text": { "runs": [
                          { "text": "Rich ", "color": "#FFFFFFFF" },
                          { "text": "text", "color": "#FFFFD479" },
                          { "text": " runs", "color": "#FF8FD0A0" }
                      ] } },
                    { "id": "custom_font", "type": "label", "x": 160, "y": 128,
                      "text": "NanoVG custom font", "color": "#FFFFD479",
                      "font": "body" },
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
                    { "id": "nine_slice", "type": "image", "x": 16, "y": 70,
                      "width": 140, "height": 34,
                      "texture": "pack:minecraft:block/diamond_block",
                      "nine_slice": [4, 4, 4, 4] },
                    { "id": "tex_panel", "type": "panel", "x": 164, "y": 70,
                      "width": 140, "height": 34,
                      "style": { "background": "pack:minecraft:block/diamond_block",
                                 "nine_slice": [4, 4, 4, 4] } },
                    { "id": "pack_block", "type": "image", "x": 16, "y": 120,
                      "width": 32, "height": 32,
                      "texture": "pack:minecraft:block/diamond_block" },
                    { "id": "builtin_error", "type": "image", "x": 64, "y": 120,
                      "width": 32, "height": 32,
                      "texture": "builtin:error" },
                    { "id": "missing", "type": "image", "x": 112, "y": 120,
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

    /**
     * Page state is saved to {@code plugins/CloudCore/state/<uuid>.json} on a worker every
     * five seconds (and on quit) so balances and toggles survive a relog. Saving never runs
     * on the main thread; a failed save keeps the session dirty for the next pass.
     */
    private void startStatePersistence() {
        if (!getConfig().getBoolean("state.persist", true)) {
            getLogger().info("Page state persistence disabled (state.persist: false)");
            return;
        }
        String backend = getConfig().getString("state.backend", "file").trim()
                .toLowerCase(java.util.Locale.ROOT);
        stateStore = createStateStore(backend);
        if (stateStore == null) {
            getLogger().warning("Page state persistence could not start; state stays in memory");
            return;
        }
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::flushDirtyState,
                100L, 100L);
        getLogger().info("Page state persistence enabled (" + backend + ")");
    }

    /**
     * Chooses the state backend. A broken SQL configuration falls back to the file store
     * instead of dropping player state for the whole session.
     */
    private com.cloudcore.server.state.PlayerStateStore createStateStore(String backend) {
        java.nio.file.Path stateRoot = getDataFolder().toPath().resolve("state");
        try {
            if (backend.equals("sqlite")) {
                Class.forName("org.sqlite.JDBC");
                String configured = getConfig().getString("state.sqlite.file", "state.db");
                String file = configured == null || configured.isBlank()
                        ? "state.db" : configured.trim();
                java.nio.file.Path database = getDataFolder().toPath().resolve(file);
                if (database.getParent() != null) {
                    java.nio.file.Files.createDirectories(database.getParent());
                }
                String url = "jdbc:sqlite:" + database.toAbsolutePath();
                com.cloudcore.server.state.SqlPlayerStateStore store =
                        new com.cloudcore.server.state.SqlPlayerStateStore(
                                com.cloudcore.server.state.SqlPlayerStateStore.Dialect.SQLITE,
                                () -> java.sql.DriverManager.getConnection(url));
                store.initialize();
                return store;
            }
            if (backend.equals("mysql")) {
                String driver = getConfig().getString("state.mysql.driver",
                        "com.mysql.cj.jdbc.Driver");
                Class.forName(driver);
                String url = getConfig().getString("state.mysql.url", "");
                String username = getConfig().getString("state.mysql.username", "");
                String password = getConfig().getString("state.mysql.password", "");
                if (url == null || url.isBlank()) {
                    throw new IllegalArgumentException("state.mysql.url is empty");
                }
                com.cloudcore.server.state.SqlPlayerStateStore store =
                        new com.cloudcore.server.state.SqlPlayerStateStore(
                                com.cloudcore.server.state.SqlPlayerStateStore.Dialect.MYSQL,
                                () -> java.sql.DriverManager.getConnection(url, username,
                                        password));
                store.initialize();
                return store;
            }
        } catch (Exception exception) {
            getLogger().log(Level.WARNING, "State backend '" + backend
                    + "' failed to start; falling back to files", exception);
        }
        return new com.cloudcore.server.state.FilePlayerStateStore(stateRoot);
    }

    /** Export all persisted page state to {@code exports/<name>.json} off the main thread. */
    private void exportState(org.bukkit.command.CommandSender sender, String name) {
        if (stateStore == null) {
            sender.sendMessage(Component.text("Page state persistence is disabled."));
            return;
        }
        java.nio.file.Path file = stateFile(name);
        if (file == null) {
            sender.sendMessage(Component.text(
                    "Usage: /cloudcore state-export <file.json> (letters, digits, . _ -)"));
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                flushDirtyState();
                int count = com.cloudcore.server.state.StateTransfer.export(stateStore, file);
                getServer().getScheduler().runTask(this, () -> sender.sendMessage(Component.text(
                        "Exported page state for " + count + " player(s) to " + file)));
            } catch (java.io.IOException exception) {
                getLogger().log(Level.WARNING, "Cannot export page state to " + file, exception);
                getServer().getScheduler().runTask(this, () -> sender.sendMessage(Component.text(
                        "State export failed: " + exception.getMessage())));
            }
        });
    }

    /** Import replaces each player's stored state; online sessions keep their live state. */
    private void importState(org.bukkit.command.CommandSender sender, String name) {
        if (stateStore == null) {
            sender.sendMessage(Component.text("Page state persistence is disabled."));
            return;
        }
        java.nio.file.Path file = stateFile(name);
        if (file == null || java.nio.file.Files.notExists(file)) {
            sender.sendMessage(Component.text("No such state export: " + name));
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                int count = com.cloudcore.server.state.StateTransfer.importFile(stateStore, file);
                getLogger().info("Imported page state for " + count + " player(s) from "
                        + file + "; online sessions keep their live state until rejoin");
                getServer().getScheduler().runTask(this, () -> sender.sendMessage(Component.text(
                        "Imported page state for " + count
                                + " player(s); online players keep live state until rejoin")));
            } catch (java.io.IOException exception) {
                getLogger().log(Level.WARNING, "Cannot import page state from " + file, exception);
                getServer().getScheduler().runTask(this, () -> sender.sendMessage(Component.text(
                        "State import failed: " + exception.getMessage())));
            }
        });
    }

    /** Resolves an export name under the plugin data directory, rejecting path traversal. */
    private java.nio.file.Path stateFile(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                || name.contains("..")) {
            return null;
        }
        String file = name.endsWith(".json") ? name : name + ".json";
        return getDataFolder().toPath().resolve("exports").resolve(file);
    }

    /** @return true when the state was written; a failure leaves the session dirty */
    private boolean saveState(java.util.UUID player, Session session) {
        if (stateStore == null) {
            return true;
        }
        long version = session.stateVersion.get();
        Map<String, Map<String, Object>> snapshot = new java.util.LinkedHashMap<>();
        session.pageState.forEach((page, values) ->
                snapshot.put(page, new java.util.LinkedHashMap<>(values)));
        try {
            stateStore.save(player, snapshot);
            // A set_state that lands while the file is being written keeps the session dirty,
            // so the next pass saves it instead of losing the change.
            if (session.stateVersion.get() == version) {
                session.stateDirty = false;
            }
            return true;
        } catch (java.io.IOException exception) {
            getLogger().log(Level.WARNING, "Could not save page state for " + player, exception);
            return false;
        }
    }

    private void flushDirtyState() {
        if (stateStore == null) {
            return;
        }
        for (Map.Entry<java.util.UUID, Session> entry : sessions.entrySet()) {
            if (entry.getValue().stateDirty) {
                saveState(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Loads persisted page state once the player joins. */
    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        if (stateStore == null) {
            return;
        }
        java.util.UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        // The session is normally created lazily on HELLO; create it now so the loaded state
        // has somewhere to go and later opens see it.
        sessions.computeIfAbsent(uuid, ignored -> new Session());
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            Map<String, Map<String, Object>> stored;
            try {
                stored = stateStore.load(uuid);
            } catch (java.io.IOException exception) {
                getLogger().log(Level.WARNING, "Could not load page state for " + name, exception);
                return;
            }
            if (stored.isEmpty()) {
                return;
            }
            getServer().getScheduler().runTask(this, () -> {
                Session session = sessions.get(uuid);
                if (session == null) {
                    return;
                }
                stored.forEach((page, values) -> session.pageState.compute(page,
                        (key, existing) -> existing == null || existing.isEmpty()
                                ? new ConcurrentHashMap<>(values) : existing));
                getLogger().info("Loaded page state for " + name + " pages=" + stored.size());
            });
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        eventLimiter.forget(event.getPlayer().getUniqueId());
        compiledPages.remove(event.getPlayer().getUniqueId());
        Session removed = sessions.remove(event.getPlayer().getUniqueId());
        if (removed != null) {
            removed.assembler.clear();
            removeSessionBossBars(removed);
            if (removed.stateDirty && stateStore != null) {
                // Save off-thread: the session is already detached, so a copy is enough.
                java.util.UUID uuid = event.getPlayer().getUniqueId();
                getServer().getScheduler().runTaskAsynchronously(this,
                        () -> saveState(uuid, removed));
            }
        }
    }

    /** Everything a custom action handler may read or trigger for the click that fired it. */
    private final class HandlerContext implements com.cloudcore.bukkit.api.ActionContext {
        private final Player player;
        private final com.cloudcore.api.page.PageLoader.CompiledPage page;
        private final String elementId;
        private final Map<String, Object> state;
        private final Session session;

        private HandlerContext(Player player,
                com.cloudcore.api.page.PageLoader.CompiledPage page, String elementId,
                Map<String, Object> state, Session session) {
            this.player = player;
            this.page = page;
            this.elementId = elementId == null ? "" : elementId;
            this.state = state;
            this.session = session;
        }

        @Override
        public Player player() {
            return player;
        }

        @Override
        public String uiId() {
            return page.document().id();
        }

        @Override
        public String elementId() {
            return elementId;
        }

        @Override
        public Map<String, Object> state() {
            return java.util.Collections.unmodifiableMap(state);
        }

        @Override
        public void message(String text) {
            if (text != null) {
                player.sendMessage(Component.text(text));
            }
        }

        @Override
        public void refresh() {
            refreshPage(player, session, page);
        }
    }

    /**
     * The service addons load through {@code Bukkit.getServicesManager()}. Opens load pages
     * the same way the {@code /cloudcore page} command does, so state, revision counting and
     * texture refs stay on one path.
     */
    private final class Api implements com.cloudcore.bukkit.api.CloudCoreApi {

        @Override
        public com.cloudcore.bukkit.api.OpenResult open(Player player, String pageId) {
            return open(player, pageId, com.cloudcore.bukkit.api.OpenOptions.defaults());
        }

        @Override
        public com.cloudcore.bukkit.api.OpenResult open(Player player, String pageId,
                com.cloudcore.bukkit.api.OpenOptions options) {
            java.util.Objects.requireNonNull(player, "player");
            java.util.Objects.requireNonNull(pageId, "pageId");
            com.cloudcore.bukkit.api.OpenOptions effective = options == null
                    ? com.cloudcore.bukkit.api.OpenOptions.defaults() : options;
            if (!getServer().isPrimaryThread()) {
                getServer().getScheduler().runTask(CloudCorePlugin.this,
                        () -> open(player, pageId, effective));
                return com.cloudcore.bukkit.api.OpenResult.SCHEDULED;
            }
            if (!isModded(player)) {
                return com.cloudcore.bukkit.api.OpenResult.NOT_MODDED;
            }
            com.cloudcore.bukkit.api.PageProvider provider = pageProviders.get(pageId);
            if (provider != null) {
                try {
                    com.cloudcore.api.page.PageLoader.CompiledPage built = provider.build(player);
                    if (built == null) {
                        getLogger().warning("Page provider '" + pageId + "' returned null");
                        return com.cloudcore.bukkit.api.OpenResult.FAILED;
                    }
                    return open(player, built, effective);
                } catch (Exception exception) {
                    getLogger().log(Level.WARNING, "Page provider '" + pageId + "' failed",
                            exception);
                    return com.cloudcore.bukkit.api.OpenResult.FAILED;
                }
            }
            java.nio.file.Path pageFile = resolvePageFile(pageId);
            if (pageFile == null) {
                getLogger().warning("API open: unknown page '" + pageId + "'");
                return com.cloudcore.bukkit.api.OpenResult.UNKNOWN_PAGE;
            }
            Session session = sessions.computeIfAbsent(player.getUniqueId(),
                    ignored -> new Session());
            Map<String, Object> state = session.pageState.computeIfAbsent(pageId,
                    ignored -> new ConcurrentHashMap<>());
            com.cloudcore.api.page.PageLoader.CompiledPage compiled;
            try {
                compiled = com.cloudcore.api.page.PageLoader.load(pagesRoot, pageFile, state);
            } catch (PageLoadException exception) {
                getLogger().warning("API open: page '" + pageId + "' failed to load: "
                        + exception.getMessage());
                return com.cloudcore.bukkit.api.OpenResult.FAILED;
            }
            SurfaceType surface = effective.surface() == null
                    ? compiled.document().surface() : effective.surface();
            if (surface != SurfaceType.CUSTOM_SCREEN) {
                getLogger().warning("API open: page '" + pageId + "' requests surface " + surface
                        + "; v1 opens custom screens only");
                return com.cloudcore.bukkit.api.OpenResult.UNSUPPORTED_SURFACE;
            }
            sendPage(player, pageId);
            return com.cloudcore.bukkit.api.OpenResult.OPENED;
        }

        @Override
        public com.cloudcore.bukkit.api.OpenResult open(Player player,
                com.cloudcore.api.page.PageLoader.CompiledPage page) {
            return open(player, page, com.cloudcore.bukkit.api.OpenOptions.defaults());
        }

        @Override
        public com.cloudcore.bukkit.api.OpenResult open(Player player,
                com.cloudcore.api.page.PageLoader.CompiledPage page,
                com.cloudcore.bukkit.api.OpenOptions options) {
            java.util.Objects.requireNonNull(player, "player");
            java.util.Objects.requireNonNull(page, "page");
            com.cloudcore.bukkit.api.OpenOptions effective = options == null
                    ? com.cloudcore.bukkit.api.OpenOptions.defaults() : options;
            if (!getServer().isPrimaryThread()) {
                getServer().getScheduler().runTask(CloudCorePlugin.this,
                        () -> open(player, page, effective));
                return com.cloudcore.bukkit.api.OpenResult.SCHEDULED;
            }
            if (!isModded(player)) {
                return com.cloudcore.bukkit.api.OpenResult.NOT_MODDED;
            }
            SurfaceType surface = effective.surface() == null
                    ? page.document().surface() : effective.surface();
            if (surface != SurfaceType.CUSTOM_SCREEN) {
                getLogger().warning("API open: code-built page '" + page.document().id()
                        + "' requests surface " + surface + "; v1 opens custom screens only");
                return com.cloudcore.bukkit.api.OpenResult.UNSUPPORTED_SURFACE;
            }
            Session session = sessions.computeIfAbsent(player.getUniqueId(),
                    ignored -> new Session());
            // The action table must be reachable from the event handler by uiId.
            putCompiledPage(player.getUniqueId(), page);
            sendCompiledPage(player, session, page.document().id(), page);
            return com.cloudcore.bukkit.api.OpenResult.OPENED;
        }

        @Override
        public void registerActionHandler(String name,
                com.cloudcore.bukkit.api.ActionHandler handler) {
            java.util.Objects.requireNonNull(handler, "handler");
            String key = com.cloudcore.server.action.ActionNames.normalise(name);
            if (BUILT_IN_ACTIONS.contains(key)) {
                throw new IllegalArgumentException("Action name '" + key + "' is built in");
            }
            if (actionHandlers.putIfAbsent(key, handler) != null) {
                throw new IllegalArgumentException("Action name '" + key + "' is registered");
            }
            getLogger().info("Registered action handler '" + key + "'");
        }

        @Override
        public boolean unregisterActionHandler(String name) {
            if (!com.cloudcore.server.action.ActionNames.isValid(name)) {
                return false;
            }
            boolean removed = actionHandlers.remove(
                    com.cloudcore.server.action.ActionNames.normalise(name)) != null;
            if (removed) {
                getLogger().info("Unregistered action handler '" + name + "'");
            }
            return removed;
        }

        @Override
        public void registerPlaceholderResolver(String namespace,
                com.cloudcore.bukkit.api.PlaceholderResolver resolver) {
            java.util.Objects.requireNonNull(resolver, "resolver");
            String key = namespace == null ? ""
                    : namespace.trim().toLowerCase(java.util.Locale.ROOT);
            if (!com.cloudcore.server.placeholder.PlaceholderToken.isValidNamespace(key)) {
                throw new IllegalArgumentException("Invalid placeholder namespace '" + namespace
                        + "': expected [a-z0-9_]{1,16}");
            }
            if (placeholderResolvers.putIfAbsent(key, resolver) != null) {
                throw new IllegalArgumentException("Placeholder namespace '" + key
                        + "' is registered");
            }
            getLogger().info("Registered placeholder resolver '" + key + "'");
        }

        @Override
        public boolean unregisterPlaceholderResolver(String namespace) {
            if (namespace == null) {
                return false;
            }
            String key = namespace.trim().toLowerCase(java.util.Locale.ROOT);
            boolean removed = placeholderResolvers.remove(key) != null;
            if (removed) {
                getLogger().info("Unregistered placeholder resolver '" + key + "'");
            }
            return removed;
        }

        @Override
        public void registerPageProvider(String pageId,
                com.cloudcore.bukkit.api.PageProvider provider) {
            java.util.Objects.requireNonNull(provider, "provider");
            if (pageId == null || pageId.isBlank() || pageId.length() > 64) {
                throw new IllegalArgumentException("Invalid page id '" + pageId + "'");
            }
            if (pageProviders.putIfAbsent(pageId, provider) != null) {
                throw new IllegalArgumentException("Page provider '" + pageId
                        + "' is registered");
            }
            getLogger().info("Registered page provider '" + pageId + "'");
        }

        @Override
        public boolean unregisterPageProvider(String pageId) {
            boolean removed = pageId != null && pageProviders.remove(pageId) != null;
            if (removed) {
                getLogger().info("Unregistered page provider '" + pageId + "'");
            }
            return removed;
        }

        @Override
        public void registerSurfaceProvider(com.cloudcore.api.surface.SurfaceProvider provider) {
            java.util.Objects.requireNonNull(provider, "provider");
            if (provider.type() == SurfaceType.UNKNOWN) {
                throw new IllegalArgumentException(
                        "UNKNOWN is not a surface; the client cannot render it");
            }
            surfaceProviders.register(provider);
            getLogger().info("Registered surface provider " + provider.type());
        }

        @Override
        public boolean unregisterSurfaceProvider(SurfaceType surface) {
            boolean removed = surfaceProviders.unregister(surface);
            if (removed) {
                getLogger().info("Unregistered surface provider " + surface);
            }
            return removed;
        }

        @Override
        public <T> void addEventListener(Class<T> type,
                java.util.function.Consumer<? super T> listener) {
            addonEvents.add(type, listener);
        }

        @Override
        public <T> boolean removeEventListener(Class<T> type,
                java.util.function.Consumer<? super T> listener) {
            return addonEvents.remove(type, listener);
        }

        @Override
        public boolean isModded(Player player) {
            Session session = player == null ? null : sessions.get(player.getUniqueId());
            return session != null && session.hello != null;
        }
    }

    private static final class PageRefresh {
        private final com.cloudcore.api.page.RefreshPlan plan;
        private volatile boolean eventDue;
        private volatile boolean inFlight;

        private PageRefresh(com.cloudcore.api.page.RefreshPlan plan) {
            this.plan = plan;
        }
    }

    private static final class Session {
        private final FragmentAssembler assembler = new FragmentAssembler(Limits.C2S);
        private Hello hello;
        private boolean pingPending;
        private int pingNonce;
        private long pingSentNanos;
        private BossBar bossBar;
        /** Sentinel bars created by a page's {@code demo_bars} list. */
        private final java.util.List<BossBar> bossBars = new java.util.ArrayList<>();
        /** The single bar a layered demo reuses for every layer. */
        private BossBar layeredBar;
        private String layerToken;
        private String layerBossName;
        private java.util.List<DemoLayer> demoLayers;
        /** Per-mille remaining strength of each layer, outermost first. */
        private double[] layerValues;
        /** Last title pushed, so an unchanged encoding costs no packet. */
        private String layerTitle;
        private org.bukkit.scheduler.BukkitTask layerTask;
        private String lastUiId;
        /** Per-page state overrides, keyed by page name, then by state key. */
        private final Map<String, Map<String, Object>> pageState = new ConcurrentHashMap<>();
        /** uiId -> page name, so a set_state action can recompile the right page. */
        private final Map<String, String> pageNames = new ConcurrentHashMap<>();
        private final Map<String, Integer> pageRevisions = new ConcurrentHashMap<>();
        /** Last document actually sent per uiId, the baseline for the next incremental diff. */
        private final Map<String, UiDocument> sentDocuments = new ConcurrentHashMap<>();
        /** Pages that asked for periodic or event-driven placeholder refreshes. */
        private final Map<String, PageRefresh> refreshes = new ConcurrentHashMap<>();
        /** Set by set_state; the persistence task writes the state and clears it. */
        private volatile boolean stateDirty;
        /** Bumped on every state write so a save in flight does not clear a newer change. */
        private final java.util.concurrent.atomic.AtomicLong stateVersion =
                new java.util.concurrent.atomic.AtomicLong();
    }
}
