package com.cloudcore.protocol;

import java.util.Objects;

/**
 * Codec for {@code TEXTURE_REF}: {@code string key, string url, string sha256, u16 width,
 * u16 height, u8 flags, u32 maxBytes, u16 ttlSeconds}.
 */
public final class TextureRefCodec {
    private TextureRefCodec() {
    }

    public static byte[] encode(TextureRef ref, Limits limits) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSink sink = new ArrayByteSink();
        ProtocolText.write(sink, ref.key(), limits);
        ProtocolText.write(sink, ref.url(), limits);
        ProtocolText.write(sink, ref.sha256(), limits);
        sink.writeShort(ref.width());
        sink.writeShort(ref.height());
        sink.writeByte(ref.flags());
        sink.writeInt((int) ref.maxBytes());
        sink.writeShort(ref.ttlSeconds());
        return sink.toByteArray();
    }

    public static TextureRef decode(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSource source = new ArrayByteSource(data);
        String key = ProtocolText.read(source, limits);
        String url = ProtocolText.read(source, limits);
        String sha256 = ProtocolText.read(source, limits);
        int width = source.readUnsignedShort();
        int height = source.readUnsignedShort();
        int flags = source.readUnsignedByte();
        int maxBytes = source.readInt();
        if (maxBytes <= 0) {
            throw new ProtocolException("TEXTURE_REF maxBytes must be positive");
        }
        int ttlSeconds = source.readUnsignedShort();
        if (source.readableBytes() != 0) {
            throw new ProtocolException("Trailing bytes in TEXTURE_REF: "
                    + source.readableBytes());
        }
        try {
            return new TextureRef(key, url, sha256, width, height, flags, maxBytes, ttlSeconds);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Invalid TEXTURE_REF: " + exception.getMessage());
        }
    }
}
