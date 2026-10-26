package com.cloudcore.protocol;

import com.cloudcore.api.BindMode;
import com.cloudcore.api.RenderMode;
import com.cloudcore.api.SurfaceType;
import java.util.Objects;

/**
 * Codecs for {@code UI_OPEN} and {@code UI_READY}.
 */
public final class UiCodec {
    private UiCodec() {
    }

    public static byte[] encodeOpen(UiOpen open, Limits limits) {
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSink sink = new ArrayByteSink();
        ProtocolText.write(sink, open.uiId(), limits);
        sink.writeVarInt(open.revision());
        sink.writeByte(open.surface().id());
        sink.writeByte(open.bindMode().id());
        sink.writeInt(open.token());
        sink.writeByte(open.renderMode().id());
        sink.writeByte(open.flags());
        ProtocolText.write(sink, open.surfaceKey(), limits);
        return sink.toByteArray();
    }

    public static UiOpen decodeOpen(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSource source = new ArrayByteSource(data);
        String uiId = ProtocolText.read(source, limits);
        int revision = source.readVarInt();
        if (revision < 0) {
            throw new ProtocolException("Invalid revision: " + revision);
        }
        SurfaceType surface = surface(source.readUnsignedByte());
        BindMode bindMode = bindMode(source.readUnsignedByte());
        int token = source.readInt();
        RenderMode renderMode = renderMode(source.readUnsignedByte());
        int flags = source.readUnsignedByte();
        String surfaceKey = ProtocolText.read(source, limits);
        requireFullyConsumed(source, "UI_OPEN");
        return new UiOpen(uiId, revision, surface, bindMode, token, renderMode, flags, surfaceKey);
    }

    public static byte[] encodeReady(UiReady ready, Limits limits) {
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSink sink = new ArrayByteSink();
        ProtocolText.write(sink, ready.uiId(), limits);
        sink.writeVarInt(ready.revision());
        sink.writeInt(ready.token());
        sink.writeByte(ready.surface().id());
        sink.writeByte(ready.state());
        ProtocolText.write(sink, ready.detail(), limits);
        return sink.toByteArray();
    }

    public static UiReady decodeReady(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSource source = new ArrayByteSource(data);
        String uiId = ProtocolText.read(source, limits);
        int revision = source.readVarInt();
        if (revision < 0) {
            throw new ProtocolException("Invalid revision: " + revision);
        }
        int token = source.readInt();
        SurfaceType surface = surface(source.readUnsignedByte());
        int state = source.readUnsignedByte();
        String detail = ProtocolText.read(source, limits);
        requireFullyConsumed(source, "UI_READY");
        return new UiReady(uiId, revision, token, surface, state, detail);
    }

    private static SurfaceType surface(int id) {
        try {
            return SurfaceType.fromId(id);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Unknown surface id: " + id);
        }
    }

    private static BindMode bindMode(int id) {
        try {
            return BindMode.fromId(id);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Unknown bind mode id: " + id);
        }
    }

    private static RenderMode renderMode(int id) {
        try {
            return RenderMode.fromId(id);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Unknown render mode id: " + id);
        }
    }

    private static void requireFullyConsumed(ByteSource source, String message) {
        if (source.readableBytes() != 0) {
            throw new ProtocolException("Trailing bytes in " + message + ": " + source.readableBytes());
        }
    }
}
