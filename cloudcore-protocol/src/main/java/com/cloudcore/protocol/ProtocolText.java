package com.cloudcore.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The shared {@code string} wire type: varint UTF-8 byte length followed by UTF-8 bytes.
 */
final class ProtocolText {
    private ProtocolText() {
    }

    static void write(ByteSink sink, String value, Limits limits) {
        Objects.requireNonNull(value, "value");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limits.maxStringBytes()) {
            throw new ProtocolException("String too long: " + bytes.length);
        }
        sink.writeVarInt(bytes.length);
        sink.writeBytes(bytes);
    }

    static String read(ByteSource source, Limits limits) {
        int length = source.readVarInt();
        if (length < 0 || length > limits.maxStringBytes()) {
            throw new ProtocolException("Invalid string length: " + length);
        }
        return new String(source.readBytes(length), StandardCharsets.UTF_8);
    }
}
