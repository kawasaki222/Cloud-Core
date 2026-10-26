package com.cloudcore.client;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiDocumentJson;
import com.cloudcore.api.document.UiDocumentValidator;
import com.cloudcore.api.document.ValidationIssue;
import com.cloudcore.api.document.ValidationResult;
import com.cloudcore.api.page.PageLoadException;
import com.cloudcore.client.net.CloudFramePayload;
import com.cloudcore.client.surface.CloseReason;
import com.cloudcore.client.surface.OpenRequest;
import com.cloudcore.client.surface.SurfaceAdapter;
import com.cloudcore.client.surface.SurfaceRegistry;
import com.cloudcore.client.surface.UiRuntime;
import com.cloudcore.protocol.FeatureFlags;
import com.cloudcore.protocol.Frame;
import com.cloudcore.protocol.FrameCodec;
import com.cloudcore.protocol.FrameFlags;
import com.cloudcore.protocol.FragmentAssembler;
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

    private static SurfaceRegistry surfaceRegistry;
    private final FragmentAssembler assembler = new FragmentAssembler(Limits.S2C);
    private final Map<String, OpenRequest> pendingOpens = new HashMap<>();
    private final Map<String, UiDefinition> pendingDefinitions = new HashMap<>();
    private volatile boolean handshakeComplete;

    @Override
    public void onInitializeClient() {
        surfaceRegistry = SurfaceRegistry.createDefault();
        PayloadTypeRegistry.playS2C().register(CloudFramePayload.TYPE, CloudFramePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CloudFramePayload.TYPE, CloudFramePayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(CloudFramePayload.TYPE,
                (payload, context) -> handlePayload(payload));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            handshakeComplete = false;
            assembler.clear();
            pendingOpens.clear();
            pendingDefinitions.clear();
            sendHello(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            handshakeComplete = false;
            assembler.clear();
            pendingOpens.clear();
            pendingDefinitions.clear();
        });

        LOGGER.info("Cloud Core client initialized (protocol {}.{})",
                ProtocolVersion.MAJOR, ProtocolVersion.MINOR);
    }

    private void handlePayload(CloudFramePayload payload) {
        try {
            Frame frame = FrameCodec.decode(payload.data(), Limits.S2C);
            if ((frame.flags() & FrameFlags.FRAGMENT) != 0) {
                Optional<Frame> complete = assembler.accept(frame);
                if (complete.isEmpty()) {
                    return;
                }
                frame = complete.get();
            }
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
            case Opcode.UI_CLOSE -> handleUiClose(frame);
            case Opcode.DIAGNOSTIC -> LOGGER.warn("Server diagnostic at opcode 0x{} ({} bytes)",
                    Integer.toHexString(frame.opcode()), frame.bodyLength());
            default -> LOGGER.info("Unhandled S2C opcode=0x{} bytes={}",
                    Integer.toHexString(frame.opcode()), frame.bodyLength());
        }
    }

    private void handleUiOpen(Frame frame) {
        UiOpen open = UiCodec.decodeOpen(frame.body(), Limits.S2C);
        OpenRequest request = new OpenRequest(
                open.surface(),
                open.uiId(),
                open.revision(),
                open.token(),
                open.bindMode(),
                open.renderMode(),
                open.flags(),
                open.surfaceKey());
        Minecraft minecraft = Minecraft.getInstance();
        if (!surfaceRegistry.canHandle(request, minecraft)) {
            UiReady ready = new UiReady(open.uiId(), open.revision(), open.token(),
                    open.surface(), UiReadyState.SURFACE_UNSUPPORTED,
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
            sendReady(request, definition.revision(), UiReadyState.OK, "");
            LOGGER.info("Opened ui={} surface={} revision={}",
                    definition.uiId(), request.surface(), definition.revision());
        } catch (RuntimeException exception) {
            failOpen(request, definition.revision(), UiReadyState.BIND_FAILED,
                    exception.getMessage());
            LOGGER.error("Failed to open ui=" + definition.uiId(), exception);
        }
    }

    private void handleUiClose(Frame frame) {
        UiClose close = UiCodec.decodeClose(frame.body(), Limits.S2C);
        pendingOpens.remove(close.uiId());
        pendingDefinitions.remove(close.uiId());
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
                request.surface(), state, truncate(detail));
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
        LOGGER.debug("Answered PING nonce=0x{}", Integer.toHexString(ping.nonce()));
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

    private static void sendFrame(Frame frame) {
        try {
            byte[] data = FrameCodec.encode(frame, Limits.C2S);
            // Paper does not necessarily advertise channels through minecraft:register, so
            // canSend() is not a reliable gate for a plugin-message transport.
            ClientPlayNetworking.send(new CloudFramePayload(data));
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
