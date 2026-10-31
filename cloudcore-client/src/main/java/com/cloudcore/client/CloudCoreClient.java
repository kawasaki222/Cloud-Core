package com.cloudcore.client;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiDocumentJson;
import com.cloudcore.api.document.UiDocumentValidator;
import com.cloudcore.api.document.ValidationIssue;
import com.cloudcore.api.document.ValidationResult;
import com.cloudcore.api.page.PageLoadException;
import com.cloudcore.client.net.CloudFramePayload;
import com.cloudcore.client.surface.CloseReason;
import com.cloudcore.client.surface.DocumentUpdates;
import com.cloudcore.client.surface.OpenRequest;
import com.cloudcore.client.surface.SurfaceAdapter;
import com.cloudcore.client.surface.SurfaceRegistry;
import com.cloudcore.client.surface.UiRuntime;
import com.cloudcore.client.surface.UpdateRevision;
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
import com.cloudcore.protocol.UiClosedReason;
import com.cloudcore.protocol.UiCodec;
import com.cloudcore.protocol.UiDefinition;
import com.cloudcore.protocol.UiOpen;
import com.cloudcore.protocol.UiReady;
import com.cloudcore.protocol.UiReadyState;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CloudCoreClient implements ClientModInitializer {

    public static final String MOD_ID = "cloudcore";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final int CURRENT_FEATURES = FeatureFlags.NONE;
    private static final boolean DIAGNOSTICS = Boolean.getBoolean("cloudcore.diagnostics");
    private static final java.util.Set<String> OBSERVED_BOSS_TITLES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicBoolean TOOLTIP_SEEN =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static SurfaceRegistry surfaceRegistry;
    /** The single initializer instance; diagnostics needs its session maps. */
    private static CloudCoreClient instance;
    private final FragmentAssembler assembler = new FragmentAssembler(Limits.S2C);
    private static final java.util.concurrent.atomic.AtomicInteger EVENT_REQUEST_ID =
            new java.util.concurrent.atomic.AtomicInteger();
    private final Map<String, OpenRequest> pendingOpens = new HashMap<>();
    private final Map<String, OpenRequest> openRequests = new HashMap<>();
    private final Map<String, Integer> openRevisions = new HashMap<>();
    private final Map<String, UiDocument> openDocuments = new HashMap<>();
    private final Map<String, UiDefinition> pendingDefinitions = new HashMap<>();
    private static volatile boolean handshakeComplete;

    @Override
    public void onInitializeClient() {
        instance = this;
        ClientLogFile.install();
        // Config first: the backend selector reads the system properties it may set.
        ClientConfig config = ClientConfig.load(net.fabricmc.loader.api.FabricLoader
                .getInstance().getConfigDir().resolve("cloudcore-client.json"));
        config.apply();
        if (config.debug()) {
            LOGGER.info("Client config requests debug logging");
        }
        com.cloudcore.client.render.RemoteTextureCache.configure(config.remoteImages(),
                key -> net.minecraft.client.Minecraft.getInstance().execute(() -> sendFrame(
                        new Frame(ProtocolVersion.MAJOR, 0, Opcode.TEXTURE_REQUEST,
                                com.cloudcore.protocol.TextureRequestCodec.encode(
                                        new com.cloudcore.protocol.TextureRequest(key),
                                        Limits.C2S)))),
                net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                        .resolve("config/cloudcore-textures"),
                config.imageCacheMiB() * 1024L * 1024L);
        surfaceRegistry = SurfaceRegistry.createDefault(this::sendPlayerClosed);
        PayloadTypeRegistry.playS2C().register(CloudFramePayload.TYPE, CloudFramePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CloudFramePayload.TYPE, CloudFramePayload.CODEC);

        // Three HUD layers: background below vanilla elements, foreground and overlay on top.
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addFirst(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "hud_background"),
                (graphics, tickCounter) -> com.cloudcore.client.surface.HudSurfaceAdapter
                        .render(graphics,
                                com.cloudcore.client.surface.HudSurfaceAdapter.LAYER_BACKGROUND));
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "hud_foreground"),
                (graphics, tickCounter) -> com.cloudcore.client.surface.HudSurfaceAdapter
                        .render(graphics,
                                com.cloudcore.client.surface.HudSurfaceAdapter.LAYER_FOREGROUND));
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "hud_overlay"),
                (graphics, tickCounter) -> com.cloudcore.client.surface.HudSurfaceAdapter
                        .render(graphics,
                                com.cloudcore.client.surface.HudSurfaceAdapter.LAYER_OVERLAY));
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "debug_overlay"),
                (graphics, tickCounter) ->
                        com.cloudcore.client.screen.CloudDebugOverlay.render(graphics));

        ClientPlayNetworking.registerGlobalReceiver(CloudFramePayload.TYPE,
                (payload, context) -> handlePayload(payload));
        ClientHotkeys.register();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            resetSessionState("join");
            sendHello(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            resetSessionState("disconnect");
        });

        LOGGER.info("Cloud Core client initialized (protocol {}.{})",
                ProtocolVersion.MAJOR, ProtocolVersion.MINOR);
    }

    /** One line per open document; the F3 overlay renders these on the right. */
    public static java.util.List<String> debugLines() {
        CloudCoreClient client = instance;
        if (client == null) {
            return java.util.List.of();
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("CloudCore " + (handshakeComplete ? "ready" : "handshaking")
                + " docs=" + client.openDocuments.size());
        int shown = 0;
        for (Map.Entry<String, UiDocument> entry : client.openDocuments.entrySet()) {
            if (shown++ >= 8) {
                lines.add("...");
                break;
            }
            int revision = client.openRevisions.getOrDefault(entry.getKey(), -1);
            lines.add(entry.getKey() + " " + entry.getValue().surface()
                    + " rev=" + revision);
        }
        return lines;
    }

    /**
     * Clears reassembly, pending opens and every surface document. Without this a client
     * that reconnects elsewhere keeps the previous server's HUD, tooltip and skins.
     */
    private void resetSessionState(String cause) {
        handshakeComplete = false;
        assembler.clear();
        pendingOpens.clear();
        pendingDefinitions.clear();
        openRequests.clear();
        openRevisions.clear();
        openDocuments.clear();
        surfaceRegistry.reset(CloseReason.DISCONNECT);
        com.cloudcore.client.render.RemoteTextureCache.reload();
        com.cloudcore.client.render.TextureReferences.resetInline();
        com.cloudcore.client.render.AnimatedTextures.reset();
        LOGGER.info("Cleared session state on {}", cause);
    }

    private void handlePayload(CloudFramePayload payload) {
        try {
            if (!handshakeComplete) {
                LOGGER.info("Received S2C payload before handshake completion ({} bytes)",
                        payload.data().length);
            }
            Frame frame = FrameCodec.decode(payload.data(), Limits.S2C);
            if ((frame.flags() & FrameFlags.FRAGMENT) != 0) {
                Optional<Frame> complete = assembler.accept(frame);
                if (complete.isEmpty()) {
                    return;
                }
                frame = complete.get();
            }
            FrameLog.record("S2C", frame.opcode(), frame.bodyLength(), System.currentTimeMillis());
            dispatch(frame);
        } catch (ProtocolException exception) {
            LOGGER.warn("Dropped malformed frame from server: {}", exception.getMessage());
        } catch (RuntimeException exception) {
            LOGGER.error("Unexpected client frame failure", exception);
        }
    }

    private void dispatch(Frame frame) {
        switch (frame.opcode()) {
            case Opcode.HELLO_ACK -> handleHelloAck(frame);
            case Opcode.PING -> handlePing(frame);
            case Opcode.UI_OPEN -> handleUiOpen(frame);
            case Opcode.UI_DEFINITION -> handleUiDefinition(frame);
            case Opcode.ELEMENT_UPDATE -> handleElementUpdate(frame);
            case Opcode.UI_CLOSE -> handleUiClose(frame);
            case Opcode.DIAGNOSTIC -> handleDiagnostic(frame);
            case Opcode.TEXTURE_REF -> handleTextureRef(frame);
            case Opcode.TEXTURE_PUT -> handleTexturePut(frame);
            case Opcode.EVENT_RESULT -> handleEventResult(frame);
            default -> LOGGER.info("Unhandled S2C opcode=0x{} bytes={}",
                    Integer.toHexString(frame.opcode()), frame.bodyLength());
        }
    }

    private void handleUiOpen(Frame frame) {
        UiOpen open = UiCodec.decodeOpen(frame.body(), Limits.S2C);
        OpenRequest request = new OpenRequest(
                open.surface(),
                open.surfaceId(),
                open.uiId(),
                open.revision(),
                open.token(),
                open.bindMode(),
                open.renderMode(),
                open.flags(),
                open.surfaceKey());
        Minecraft minecraft = Minecraft.getInstance();
        if (request.hasUnknownMode()) {
            UiReady ready = new UiReady(open.uiId(), open.revision(), open.token(),
                    open.surface(), open.surfaceId(), UiReadyState.SURFACE_UNSUPPORTED,
                    "Unsupported bind/render mode");
            sendFrame(new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_READY,
                    UiCodec.encodeReady(ready, Limits.C2S)));
            LOGGER.warn("Rejected UI_OPEN for unknown mode bind={} render={} ui={}",
                    open.bindMode(), open.renderMode(), open.uiId());
            return;
        }
        if (!surfaceRegistry.canHandle(request, minecraft)) {
            UiReady ready = new UiReady(open.uiId(), open.revision(), open.token(),
                    open.surface(), open.surfaceId(), UiReadyState.SURFACE_UNSUPPORTED,
                    "Surface not implemented: " + open.surface());
            sendFrame(new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_READY,
                    UiCodec.encodeReady(ready, Limits.C2S)));
            LOGGER.warn("Rejected UI_OPEN for unsupported surface {} ui={}",
                    open.surface(), open.uiId());
            return;
        }
        pendingOpens.put(open.uiId(), request);
        LOGGER.info("UI_OPEN queued for {} ui={} revision={} surfaceKey={}",
                open.surface(), open.uiId(), open.revision(), open.surfaceKey());

        UiDefinition earlyDefinition = pendingDefinitions.remove(open.uiId());
        if (earlyDefinition != null) {
            openDefinition(request, earlyDefinition);
        }
    }

    private void handleUiDefinition(Frame frame) {
        UiDefinition definition = UiCodec.decodeDefinition(frame.body(), Limits.S2C);
        OpenRequest request = pendingOpens.get(definition.uiId());
        if (request == null) {
            OpenRequest open = openRequests.get(definition.uiId());
            if (open != null) {
                int current = openRevisions.getOrDefault(definition.uiId(), -1);
                if (definition.revision() <= current) {
                    LOGGER.debug("Ignoring stale update ui={} revision={} current={}",
                            definition.uiId(), definition.revision(), current);
                    return;
                }
                LOGGER.info("Updating ui={} revision={}", definition.uiId(),
                        definition.revision());
                openDefinition(open, definition);
                return;
            }
            if (pendingDefinitions.size() < 64) {
                pendingDefinitions.put(definition.uiId(), definition);
            }
            LOGGER.warn("UI_DEFINITION arrived before UI_OPEN for ui={} revision={}",
                    definition.uiId(), definition.revision());
            return;
        }
        openDefinition(request, definition);
    }

    private void openDefinition(OpenRequest request, UiDefinition definition) {
        if (definition.revision() < request.revision()) {
            LOGGER.warn("Ignoring stale UI_DEFINITION ui={} revision={} (current {})",
                    definition.uiId(), definition.revision(), request.revision());
            return;
        }
        if (definition.encoding() > 1) {
            failOpen(request, definition.revision(), UiReadyState.DOCUMENT_INVALID,
                    "Unsupported document encoding " + definition.encoding());
            return;
        }

        UiDocument document;
        try {
            document = UiDocumentJson.parse(definition.data());
        } catch (PageLoadException exception) {
            failOpen(request, definition.revision(), UiReadyState.DOCUMENT_INVALID,
                    exception.getMessage());
            LOGGER.warn("UI_DEFINITION parse failed ui={} revision={}: {}",
                    definition.uiId(), definition.revision(), exception.getMessage());
            return;
        }

        ValidationResult validation = UiDocumentValidator.validate(document);
        if (!validation.valid()) {
            ValidationIssue issue = validation.errors().get(0);
            failOpen(request, definition.revision(), UiReadyState.DOCUMENT_INVALID,
                    issue.code() + " " + issue.path() + ": " + issue.message());
            LOGGER.warn("UI_DEFINITION validation failed ui={} revision={} issues={}",
                    definition.uiId(), definition.revision(), validation.errors());
            return;
        }
        if (!document.id().equals(definition.uiId())
                || document.surface() != request.surface()) {
            failOpen(request, definition.revision(), UiReadyState.DOCUMENT_INVALID,
                    "Document id/surface does not match UI_OPEN");
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        SurfaceAdapter adapter = surfaceRegistry.get(request.surface());
        if (!adapter.canHandle(request, minecraft)) {
            failOpen(request, definition.revision(), UiReadyState.SURFACE_UNSUPPORTED,
                    "Surface not implemented: " + request.surface());
            return;
        }
        try {
            adapter.open(request, new UiRuntime(minecraft, request, document));
            pendingOpens.remove(definition.uiId());
            openRequests.put(definition.uiId(), request);
            openRevisions.put(definition.uiId(), definition.revision());
            openDocuments.put(definition.uiId(), document);
            sendReady(request, definition.revision(), UiReadyState.OK, "");
            LOGGER.info("Opened ui={} surface={} revision={}",
                    definition.uiId(), request.surface(), definition.revision());
        } catch (RuntimeException exception) {
            failOpen(request, definition.revision(), UiReadyState.BIND_FAILED,
                    exception.getMessage());
            LOGGER.error("Failed to open ui=" + definition.uiId(), exception);
        }
    }

    /**
     * Applies an incremental revision. Strict ordering: only {@code current + 1} is accepted,
     * so a lost update can never be mistaken for a complete document. A gap leaves the current
     * document alone until the server sends a full {@code UI_DEFINITION} again.
     */
    private void handleElementUpdate(Frame frame) {
        ElementUpdate update = ElementUpdateCodec.decode(frame.body(), Limits.S2C);
        OpenRequest request = openRequests.get(update.uiId());
        UiDocument current = openDocuments.get(update.uiId());
        if (request == null || current == null) {
            LOGGER.warn("[CC-UPDATE-002] ELEMENT_UPDATE without an open document ui={} revision={}",
                    update.uiId(), update.revision());
            return;
        }
        int currentRevision = openRevisions.getOrDefault(update.uiId(), -1);
        UpdateRevision.Decision decision = UpdateRevision.decide(currentRevision,
                update.revision());
        if (decision == UpdateRevision.Decision.STALE) {
            LOGGER.debug("Ignoring stale ELEMENT_UPDATE ui={} revision={} (current {})",
                    update.uiId(), update.revision(), currentRevision);
            return;
        }
        if (decision == UpdateRevision.Decision.GAP) {
            LOGGER.warn("[CC-UPDATE-002] ELEMENT_UPDATE gap ui={} revision={} current={}; "
                    + "waiting for a full document", update.uiId(), update.revision(),
                    currentRevision);
            return;
        }
        DocumentUpdates.Result result = DocumentUpdates.apply(current, update.entries());
        if (!result.applied()) {
            LOGGER.warn("[CC-UPDATE-003] ELEMENT_UPDATE rejected ui={} revision={}: {}",
                    update.uiId(), update.revision(), result.problem());
            return;
        }
        ValidationResult validation = UiDocumentValidator.validate(result.document());
        if (!validation.valid()) {
            LOGGER.warn("[CC-UPDATE-003] ELEMENT_UPDATE produced an invalid document ui={} "
                    + "revision={} issues={}", update.uiId(), update.revision(),
                    validation.errors());
            return;
        }
        SurfaceAdapter adapter = surfaceRegistry.get(request.surface());
        if (adapter == null || !adapter.update(update.uiId(), result.document())) {
            LOGGER.warn("[CC-UPDATE-004] Surface {} cannot apply an incremental update ui={}",
                    request.surface(), update.uiId());
            return;
        }
        openDocuments.put(update.uiId(), result.document());
        openRevisions.put(update.uiId(), update.revision());
        LOGGER.debug("Applied ELEMENT_UPDATE ui={} revision={} entries={}", update.uiId(),
                update.revision(), update.entries().size());
    }

    private void handleEventResult(Frame frame) {
        com.cloudcore.protocol.EventResult result =
                com.cloudcore.protocol.EventResultCodec.decode(frame.body(), Limits.S2C);
        if (result.status() == com.cloudcore.protocol.EventResult.OK) {
            LOGGER.debug("EVENT_RESULT request={} status={}", result.requestId(),
                    result.message());
        } else {
            LOGGER.warn("EVENT_RESULT request={} status={} ({})", result.requestId(),
                    result.status(), result.message());
        }
    }

    /** URL images: fetch on a background thread, never inside the render loop. */
    private void handleTextureRef(Frame frame) {
        com.cloudcore.protocol.TextureRef ref =
                com.cloudcore.protocol.TextureRefCodec.decode(frame.body(), Limits.S2C);
        com.cloudcore.client.render.RemoteTextureCache.request(ref);
        LOGGER.info("TEXTURE_REF key={} url={} maxBytes={}", ref.key(), ref.url(),
                ref.maxBytes());
    }

    private void handleTexturePut(Frame frame) {
        com.cloudcore.protocol.TexturePut put;
        try {
            put = com.cloudcore.protocol.TexturePutCodec.decode(frame.body(), Limits.S2C);
        } catch (ProtocolException exception) {
            LOGGER.warn("Dropped malformed TEXTURE_PUT: {}", exception.getMessage());
            return;
        }
        byte[] data = put.data();
        if (data.length < 8 || (data[0] & 0xFF) != 0x89 || data[1] != 'P'
                || data[2] != 'N' || data[3] != 'G') {
            LOGGER.warn("[CC-ASSET-019] TEXTURE_PUT {} is not a PNG", put.key());
            return;
        }
        if (crc32(data) != put.crc32()) {
            LOGGER.warn("[CC-ASSET-020] TEXTURE_PUT {} failed its CRC check", put.key());
            return;
        }
        com.cloudcore.client.render.RemoteTextureCache.put(put);
        LOGGER.info("TEXTURE_PUT key={} {}x{} format={}", put.key(), put.width(),
                put.height(), put.format());
    }

    private static int crc32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }

    private void handleDiagnostic(Frame frame) {
        Diagnostic diagnostic = DiagnosticCodec.decode(frame.body(), Limits.S2C);
        String line = "[{}] scope={} trace=0x{} {} | {}";
        String trace = Integer.toHexString(diagnostic.traceId());
        if (diagnostic.severity() >= DiagnosticSeverity.ERROR) {
            LOGGER.error(line, diagnostic.code(), diagnostic.scope(), trace,
                    diagnostic.message(), diagnostic.detail());
        } else if (diagnostic.severity() == DiagnosticSeverity.WARN) {
            LOGGER.warn(line, diagnostic.code(), diagnostic.scope(), trace,
                    diagnostic.message(), diagnostic.detail());
        } else {
            LOGGER.info(line, diagnostic.code(), diagnostic.scope(), trace,
                    diagnostic.message(), diagnostic.detail());
        }
    }

    /**
     * Sends one UI event. The action id comes from the document's {@code events} map, so the
     * client never decides what an action does.
     */
    public static void sendEvent(String uiId, String elementId, int actionId,
            java.util.Map<String, com.cloudcore.protocol.WireValue> payload) {
        if (!handshakeComplete) {
            LOGGER.debug("Dropping event before handshake: {}/{}", uiId, elementId);
            return;
        }
        int requestId = EVENT_REQUEST_ID.incrementAndGet();
        com.cloudcore.protocol.UiEvent event = new com.cloudcore.protocol.UiEvent(
                requestId, uiId, elementId, actionId, payload);
        sendFrame(new Frame(ProtocolVersion.MAJOR, 0, Opcode.EVENT,
                com.cloudcore.protocol.EventCodec.encode(event, Limits.C2S)));
        LOGGER.debug("Sent EVENT request={} ui={} element={} action={}",
                requestId, uiId, elementId, actionId);
    }

    private void sendPlayerClosed(String uiId) {
        UiClosed closed = new UiClosed(uiId, UiClosedReason.PLAYER);
        sendFrame(new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_CLOSED,
                UiCodec.encodeClosed(closed, Limits.C2S)));
    }

    private void handleUiClose(Frame frame) {
        UiClose close = UiCodec.decodeClose(frame.body(), Limits.S2C);
        pendingOpens.remove(close.uiId());
        pendingDefinitions.remove(close.uiId());
        openRequests.remove(close.uiId());
        openRevisions.remove(close.uiId());
        openDocuments.remove(close.uiId());
        CloseReason reason = switch (close.reason()) {
            case 0, 1 -> CloseReason.SERVER;
            case 2 -> CloseReason.REPLACED;
            default -> CloseReason.ERROR;
        };
        surfaceRegistry.close(close.uiId(), reason);
        LOGGER.info("UI_CLOSE ui={} reason={}", close.uiId(), close.reason());
    }

    private void failOpen(OpenRequest request, int revision, int state, String detail) {
        pendingOpens.remove(request.uiId());
        sendReady(request, revision, state, detail);
    }

    private void sendReady(OpenRequest request, int revision, int state, String detail) {
        UiReady ready = new UiReady(request.uiId(), revision, request.token(),
                request.surface(), request.surfaceId(), state, truncate(detail));
        sendFrame(new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_READY,
                UiCodec.encodeReady(ready, Limits.C2S)));
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private void handleHelloAck(Frame frame) {
        HelloAck ack = HandshakeCodec.decodeHelloAck(frame.body(), Limits.S2C);
        if (!ProtocolVersion.isCompatible(ack.protoMajor())) {
            handshakeComplete = false;
            LOGGER.warn("Server protocol {}.{} is incompatible with client {}.{}",
                    ack.protoMajor(), ack.protoMinor(),
                    ProtocolVersion.MAJOR, ProtocolVersion.MINOR);
            return;
        }
        handshakeComplete = true;
        LOGGER.info("Server handshake accepted: protocol {}.{}, features=0x{}, maxFrameBytes={}, message={}",
                ack.protoMajor(), ack.protoMinor(), Integer.toHexString(ack.acceptedFeatures()),
                ack.maxFrameBytes(), ack.serverMessage());
    }

    private void handlePing(Frame frame) {
        Ping ping = HandshakeCodec.decodePing(frame.body());
        Pong pong = new Pong(ping.nonce(), System.currentTimeMillis());
        Frame response = new Frame(ProtocolVersion.MAJOR, 0, Opcode.PONG,
                HandshakeCodec.encodePong(pong));
        sendFrame(response);
        LOGGER.info("Received PING nonce=0x{}", Integer.toHexString(ping.nonce()));
    }

    /**
     * Cursor position in GUI pixels, for the tooltip scrollbar's hover state and thumb drag.
     * Called from {@code MouseHandler#turnPlayer}, so it runs once per mouse move, not per
     * frame.
     */
    public static void onMouseMove(double mouseX, double mouseY) {
        com.cloudcore.client.surface.TooltipSurfaceAdapter.onMouseMove(mouseX, mouseY);
    }

    /**
     * Press and release for the tooltip scrollbar. Only the left button is considered; a
     * press on the bar starts a thumb drag and a release ends it. Clicks elsewhere are
     * untouched, so screens and containers keep their normal behaviour.
     */
    public static void onMouseButton(int button, int action) {
        if (button != 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // onButton already received window pixels; scale them the same way onMove does.
        double mouseX = minecraft.mouseHandler.getScaledXPos(minecraft.getWindow());
        double mouseY = minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());
        if (action == 1) {
            com.cloudcore.client.surface.TooltipSurfaceAdapter.onMouseDown(mouseX, mouseY);
        } else if (action == 0) {
            com.cloudcore.client.surface.TooltipSurfaceAdapter.onMouseUp();
        }
    }

    public static void onScroll(double xOffset, double yOffset) {
        if (com.cloudcore.client.surface.TooltipSurfaceAdapter.onScroll(yOffset)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (yOffset != 0.0
                && minecraft.screen
                        instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
            if (DIAGNOSTICS) {
                LOGGER.info("Scroll observed screen={} x={} y={}",
                        minecraft.screen.getClass().getName(), xOffset, yOffset);
            } else {
                LOGGER.debug("Scroll observed screen={} x={} y={}",
                        minecraft.screen.getClass().getName(), xOffset, yOffset);
            }
        }
    }

    public static void onTooltip(int lines) {
        if (lines <= 0) {
            return;
        }
        if (TOOLTIP_SEEN.compareAndSet(false, true)) {
            LOGGER.info("Tooltip observed lines={}", lines);
        } else {
            LOGGER.debug("Tooltip observed lines={}", lines);
        }
    }

    public static void onBossBar(String title) {
        // Called from BossHealthOverlay's render hook, so this runs every frame. A title is
        // logged once when first seen and then stays quiet: an unchanged boss bar is not an
        // event, and logging it per frame produced ~180 lines/s and filled the 10 MiB log
        // ring in minutes.
        if (title == null || !title.contains("\u0000CCB:")) {
            if (title != null && OBSERVED_BOSS_TITLES.size() < 64
                    && OBSERVED_BOSS_TITLES.add(title)) {
                LOGGER.debug("Boss bar observed title={}", title);
            }
            return;
        }
        String printable = title.replace('\u0000', '|');
        if (OBSERVED_BOSS_TITLES.size() >= 64 || !OBSERVED_BOSS_TITLES.add(title)) {
            return;
        }
        LOGGER.info("Boss bar observed title={}", printable);
    }

    public static void onScreenChanged(net.minecraft.client.gui.screens.Screen screen) {
        if (screen instanceof com.cloudcore.client.screen.CloudScreen
                && Boolean.getBoolean("cloudcore.nanovg.spike")) {
            com.cloudcore.client.render.NanoVgSpike.runOnce();
        }
        if (screen == null) {
            LOGGER.debug("Screen changed: null");
        } else if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                || screen instanceof com.cloudcore.client.screen.CloudScreen) {
            if (DIAGNOSTICS) {
                LOGGER.info("Screen changed: {}", screen.getClass().getName());
            } else {
                LOGGER.debug("Screen changed: {}", screen.getClass().getName());
            }
        } else {
            LOGGER.debug("Screen changed: {}", screen.getClass().getName());
        }
    }

    private void sendHello(Minecraft client) {
        Hello hello = new Hello(
                ProtocolVersion.MAJOR,
                ProtocolVersion.MINOR,
                modVersion(),
                CURRENT_FEATURES,
                client.getLanguageManager().getSelected(),
                client.getWindow().getGuiScale(),
                client.getWindow().getGuiScaledWidth(),
                client.getWindow().getGuiScaledHeight());
        byte[] body = HandshakeCodec.encodeHello(hello, Limits.C2S);
        Frame frame = new Frame(ProtocolVersion.MAJOR, 0, Opcode.HELLO, body);
        sendFrame(frame);
        LOGGER.info("Sent HELLO protocol {}.{} features=0x{} locale={}",
                hello.protoMajor(), hello.protoMinor(),
                Integer.toHexString(hello.features()), hello.locale());
    }

    /**
     * Builds the sanitised dump described in docs/17. Kept here so it can read the session
     * state (handshake, pending opens) without exposing setters.
     *
     * @return the zip, or {@code null} when writing failed (reason logged)
     */
    static java.nio.file.Path writeDiagnostics() {
        Minecraft minecraft = Minecraft.getInstance();
        java.nio.file.Path logs = minecraft.gameDirectory.toPath().resolve("logs");
        java.nio.file.Path config = FabricLoader.getInstance().getConfigDir()
                .resolve("cloudcore-client.json");
        java.util.Map<String, String> state = new java.util.LinkedHashMap<>();
        state.put("cloudcore", modVersion());
        state.put("minecraft", FabricLoader.getInstance().getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("?"));
        state.put("fabric_loader", FabricLoader.getInstance().getModContainer("fabricloader")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("?"));
        state.put("nanovg", Boolean.toString(Boolean.getBoolean("cloudcore.nanovg")));
        state.put("handshake", Boolean.toString(handshakeComplete));
        CloudCoreClient current = instance;
        state.put("pending_opens",
                Integer.toString(current == null ? 0 : current.pendingOpens.size()));
        state.put("open_requests",
                Integer.toString(current == null ? 0 : current.openRequests.size()));
        state.put("gui_scale", Integer.toString(minecraft.getWindow().getGuiScale()));
        state.put("gui_size", minecraft.getWindow().getGuiScaledWidth() + "x"
                + minecraft.getWindow().getGuiScaledHeight());
        try {
            return ClientDump.write(logs, config, FrameLog.snapshot(), state,
                    System.currentTimeMillis());
        } catch (java.io.IOException exception) {
            LOGGER.error("[CC-DIAG-001] writing diagnostics dump failed", exception);
            return null;
        }
    }

    private static void sendFrame(Frame frame) {
        try {
            FrameLog.record("C2S", frame.opcode(), frame.bodyLength(),
                    System.currentTimeMillis());
            Frame outbound = FrameCodec.compressIfUseful(frame);
            int fragmentId = Fragmenter.randomFragmentId(java.util.concurrent.ThreadLocalRandom.current());
            for (Frame part : Fragmenter.fragment(outbound, Limits.C2S, fragmentId)) {
                byte[] data = FrameCodec.encode(part, Limits.C2S);
                // Paper does not necessarily advertise channels through minecraft:register,
                // so canSend() is not a reliable gate for a plugin-message transport.
                ClientPlayNetworking.send(new CloudFramePayload(data));
            }
        } catch (ProtocolException | IllegalStateException exception) {
            LOGGER.warn("Failed to send opcode 0x{}: {}",
                    Integer.toHexString(frame.opcode()), exception.getMessage());
        }
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
    }

    public boolean isHandshakeComplete() {
        return handshakeComplete;
    }

    public static SurfaceRegistry surfaces() {
        if (surfaceRegistry == null) {
            throw new IllegalStateException("Cloud Core client is not initialized");
        }
        return surfaceRegistry;
    }
}
