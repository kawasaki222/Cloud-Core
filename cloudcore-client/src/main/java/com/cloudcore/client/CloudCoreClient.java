package com.cloudcore.client;

import com.cloudcore.client.net.CloudFramePayload;
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

    private final FragmentAssembler assembler = new FragmentAssembler(Limits.S2C);
    private volatile boolean handshakeComplete;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playS2C().register(CloudFramePayload.TYPE, CloudFramePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CloudFramePayload.TYPE, CloudFramePayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(CloudFramePayload.TYPE,
                (payload, context) -> context.client().execute(() -> handlePayload(payload)));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            handshakeComplete = false;
            assembler.clear();
            sendHello(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            handshakeComplete = false;
            assembler.clear();
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
            case Opcode.DIAGNOSTIC -> LOGGER.warn("Server diagnostic at opcode 0x{} ({} bytes)",
                    Integer.toHexString(frame.opcode()), frame.bodyLength());
            default -> LOGGER.debug("S2C opcode=0x{} bytes={}",
                    Integer.toHexString(frame.opcode()), frame.bodyLength());
        }
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
            if (!ClientPlayNetworking.canSend(CloudFramePayload.TYPE)) {
                LOGGER.warn("Cannot send opcode 0x{}: channel is not available",
                        Integer.toHexString(frame.opcode()));
                return;
            }
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
}
