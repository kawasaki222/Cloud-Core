package com.cloudcore.protocol;

import java.util.Objects;

/** Codec for {@code TEXTURE_REQUEST}: {@code string key}. */
public final class TextureRequestCodec {
    private TextureRequestCodec() {
    }

    public static byte[] encode(TextureRequest request, Limits limits) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSink sink = new ArrayByteSink();
        ProtocolText.write(sink, request.key(), limits);
        return sink.toByteArray();
    }

    public static TextureRequest decode(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSource source = new ArrayByteSource(data);
        String key = ProtocolText.read(source, limits);
        if (source.readableBytes() != 0) {
            throw new ProtocolException("Trailing bytes in TEXTURE_REQUEST: "
                    + source.readableBytes());
        }
        try {
            return new TextureRequest(key);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Invalid TEXTURE_REQUEST: " + exception.getMessage());
        }
    }
}
