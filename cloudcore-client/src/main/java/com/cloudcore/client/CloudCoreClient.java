package com.cloudcore.client;

import com.cloudcore.client.net.CloudFramePayload;
import com.cloudcore.protocol.Frame;
import com.cloudcore.protocol.FrameCodec;
import com.cloudcore.protocol.Limits;
import com.cloudcore.protocol.ProtocolException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CloudCoreClient implements ClientModInitializer {

    public static final String MOD_ID = "cloudcore";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playS2C().register(CloudFramePayload.TYPE, CloudFramePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CloudFramePayload.TYPE, CloudFramePayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(CloudFramePayload.TYPE, (payload, context) -> {
            try {
                Frame frame = FrameCodec.decode(payload.data(), Limits.S2C);
                LOGGER.debug("S2C opcode=0x{} bytes={}",
                        Integer.toHexString(frame.opcode()), frame.bodyLength());
            } catch (ProtocolException exception) {
                LOGGER.warn("Dropped malformed frame from server", exception);
            }
        });

        LOGGER.info("Cloud Core client initialized");
    }
}
