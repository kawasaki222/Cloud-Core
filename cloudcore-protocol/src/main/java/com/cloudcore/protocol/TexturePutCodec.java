package com.cloudcore.protocol;

import java.util.Objects;

/**
 * Codec for {@code TEXTURE_PUT}: {@code string key, u16 width, u16 height, u8 format,
 * u32 crc32, bytes data, u16 frameCount, u8 loop, u16[] frameDelaysMs}.
 */
public final class TexturePutCodec {
    private TexturePutCodec() {
    }

    public static byte[] encode(TexturePut put, Limits limits) {
        Objects.requireNonNull(put, "put");
        Objects.requireNonNull(limits, "limits");
        byte[] data = put.data();
        if (data.length > TexturePut.MAX_DATA_BYTES) {
            throw new ProtocolException("TEXTURE_PUT data too large: " + data.length);
        }
        ArrayByteSink sink = new ArrayByteSink();
        ProtocolText.write(sink, put.key(), limits);
        sink.writeShort(put.width());
        sink.writeShort(put.height());
        sink.writeByte(put.format());
        sink.writeInt(put.crc32());
        sink.writeVarInt(data.length);
        sink.writeBytes(data);
        sink.writeShort(put.frameCount());
        sink.writeByte(put.loop());
        int[] delays = put.frameDelaysMs();
        sink.writeVarInt(delays.length);
        for (int delay : delays) {
            sink.writeShort(delay);
        }
        return sink.toByteArray();
    }

    public static TexturePut decode(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSource source = new ArrayByteSource(data);
        String key = ProtocolText.read(source, limits);
        int width = source.readUnsignedShort();
        int height = source.readUnsignedShort();
        int format = source.readUnsignedByte();
        int crc32 = source.readInt();
        int length = source.readVarInt();
        if (length <= 0 || length > TexturePut.MAX_DATA_BYTES) {
            throw new ProtocolException("Invalid TEXTURE_PUT data length: " + length);
        }
        byte[] bytes = source.readBytes(length);
        int frameCount = source.readUnsignedShort();
        int loop = source.readUnsignedByte();
        int delayCount = source.readVarInt();
        if (delayCount < 0 || delayCount > TexturePut.MAX_FRAMES) {
            throw new ProtocolException("Invalid TEXTURE_PUT delay count: " + delayCount);
        }
        int[] delays = new int[delayCount];
        for (int index = 0; index < delayCount; index++) {
            delays[index] = source.readUnsignedShort();
        }
        if (source.readableBytes() != 0) {
            throw new ProtocolException("Trailing bytes in TEXTURE_PUT: "
                    + source.readableBytes());
        }
        try {
            return new TexturePut(key, width, height, format, crc32, bytes, frameCount, loop,
                    delays);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Invalid TEXTURE_PUT: " + exception.getMessage());
        }
    }
}
