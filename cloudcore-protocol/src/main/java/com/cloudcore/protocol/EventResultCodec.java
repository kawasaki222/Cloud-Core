package com.cloudcore.protocol;

import java.util.Objects;

/**
 * Codec for {@code EVENT_RESULT}: {@code varint requestId, u8 status, string message}.
 */
public final class EventResultCodec {
    private EventResultCodec() {
    }

    public static byte[] encode(EventResult result, Limits limits) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSink sink = new ArrayByteSink();
        sink.writeVarInt(result.requestId());
        sink.writeByte(result.status());
        ProtocolText.write(sink, result.message(), limits);
        return sink.toByteArray();
    }

    public static EventResult decode(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSource source = new ArrayByteSource(data);
        int requestId = source.readVarInt();
        if (requestId < 0) {
            throw new ProtocolException("Invalid EVENT_RESULT requestId: " + requestId);
        }
        int status = source.readUnsignedByte();
        String message = ProtocolText.read(source, limits);
        if (source.readableBytes() != 0) {
            throw new ProtocolException("Trailing bytes in EVENT_RESULT: "
                    + source.readableBytes());
        }
        return new EventResult(requestId, status, message);
    }
}
