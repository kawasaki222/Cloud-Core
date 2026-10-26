package com.cloudcore.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Codec for the client {@code EVENT} frame: {@code varint requestId, string uiId,
 * string elementId, u16 actionId, varint payloadLen, WireValue payload-map}.
 */
public final class EventCodec {
    private EventCodec() {
    }

    public static byte[] encode(UiEvent event, Limits limits) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSink sink = new ArrayByteSink();
        sink.writeVarInt(event.requestId());
        ProtocolText.write(sink, event.uiId(), limits);
        ProtocolText.write(sink, event.elementId(), limits);
        sink.writeShort(event.actionId());
        Map<String, WireValue> map = new LinkedHashMap<>(event.payload());
        byte[] payload = WireValueCodec.encode(
                new WireValue.MapValue(map), limits);
        sink.writeVarInt(payload.length);
        sink.writeBytes(payload);
        return sink.toByteArray();
    }

    public static UiEvent decode(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSource source = new ArrayByteSource(data);
        int requestId = source.readVarInt();
        if (requestId < 0) {
            throw new ProtocolException("Invalid requestId: " + requestId);
        }
        String uiId = ProtocolText.read(source, limits);
        String elementId = ProtocolText.read(source, limits);
        int actionId = source.readUnsignedShort();
        int payloadLength = source.readVarInt();
        if (payloadLength < 0 || payloadLength > limits.maxFrameBytes()) {
            throw new ProtocolException("Invalid event payload length: " + payloadLength);
        }
        byte[] payloadBytes = source.readBytes(payloadLength);
        WireValue payload = WireValueCodec.decode(payloadBytes, limits);
        if (!(payload instanceof WireValue.MapValue map)) {
            throw new ProtocolException("EVENT payload must be a map");
        }
        if (source.readableBytes() != 0) {
            throw new ProtocolException("Trailing bytes in EVENT: " + source.readableBytes());
        }
        return new UiEvent(requestId, uiId, elementId, actionId, map.values());
    }
}
