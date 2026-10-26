package com.cloudcore.client.net;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CloudFramePayload(byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CloudFramePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("cloudcore", "main"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CloudFramePayload> CODEC =
            StreamCodec.of(
                    (buffer, payload) -> buffer.writeBytes(payload.data()),
                    buffer -> {
                        byte[] data = new byte[buffer.readableBytes()];
                        buffer.readBytes(data);
                        return new CloudFramePayload(data);
                    });

    public CloudFramePayload {
        Objects.requireNonNull(data, "data");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
