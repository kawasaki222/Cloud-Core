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
        sink.writeByte(open.surfaceId());
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
        int surfaceId = source.readUnsignedByte();
        SurfaceType surface = SurfaceType.fromIdOrUnknown(surfaceId);
        BindMode bindMode = bindMode(source.readUnsignedByte());
        int token = source.readInt();
        RenderMode renderMode = renderMode(source.readUnsignedByte());
        int flags = source.readUnsignedByte();
        String surfaceKey = ProtocolText.read(source, limits);
        requireFullyConsumed(source, "UI_OPEN");
        return new UiOpen(uiId, revision, surface, surfaceId, bindMode, token,
                renderMode, flags, surfaceKey);
    }

    public static byte[] encodeReady(UiReady ready, Limits limits) {
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSink sink = new ArrayByteSink();
        ProtocolText.write(sink, ready.uiId(), limits);
        sink.writeVarInt(ready.revision());
        sink.writeInt(ready.token());
        sink.writeByte(ready.surfaceId());
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
        int surfaceId = source.readUnsignedByte();
        SurfaceType surface = SurfaceType.fromIdOrUnknown(surfaceId);
        int state = source.readUnsignedByte();
        String detail = ProtocolText.read(source, limits);
        requireFullyConsumed(source, "UI_READY");
        return new UiReady(uiId, revision, token, surface, surfaceId, state, detail);
    }

    public static byte[] encodeDefinition(UiDefinition definition, Limits limits) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(limits, "limits");
        byte[] data = definition.data();
        if (data.length > limits.maxDecompressedBytes()) {
            throw new ProtocolException("UI_DEFINITION data exceeds limit: " + data.length);
        }
        ArrayByteSink sink = new ArrayByteSink();
        ProtocolText.write(sink, definition.uiId(), limits);
        sink.writeVarInt(definition.revision());
        sink.writeByte(definition.encoding());
        sink.writeVarInt(data.length);
        sink.writeBytes(data);
        return sink.toByteArray();
    }

    public static UiDefinition decodeDefinition(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSource source = new ArrayByteSource(data);
        String uiId = ProtocolText.read(source, limits);
        int revision = source.readVarInt();
        if (revision < 0) {
            throw new ProtocolException("Invalid revision: " + revision);
        }
        int encoding = source.readUnsignedByte();
        int length = source.readVarInt();
        if (length < 0 || length > limits.maxDecompressedBytes()) {
            throw new ProtocolException("Invalid UI_DEFINITION data length: " + length);
        }
        byte[] document = source.readBytes(length);
        requireFullyConsumed(source, "UI_DEFINITION");
        return new UiDefinition(uiId, revision, encoding, document);
    }

    public static byte[] encodeClose(UiClose close, Limits limits) {
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSink sink = new ArrayByteSink();
        ProtocolText.write(sink, close.uiId(), limits);
        sink.writeByte(close.reason());
        return sink.toByteArray();
    }

    public static UiClose decodeClose(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSource source = new ArrayByteSource(data);
        String uiId = ProtocolText.read(source, limits);
        int reason = source.readUnsignedByte();
        requireFullyConsumed(source, "UI_CLOSE");
        return new UiClose(uiId, reason);
    }

    public static byte[] encodeClosed(UiClosed closed, Limits limits) {
        Objects.requireNonNull(closed, "closed");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSink sink = new ArrayByteSink();
        ProtocolText.write(sink, closed.uiId(), limits);
        sink.writeByte(closed.reason());
        return sink.toByteArray();
    }

    public static UiClosed decodeClosed(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSource source = new ArrayByteSource(data);
        String uiId = ProtocolText.read(source, limits);
        int reason = source.readUnsignedByte();
        requireFullyConsumed(source, "UI_CLOSED");
        return new UiClosed(uiId, reason);
    }

    private static BindMode bindMode(int id) {
        // Forward compatible: a newer server may bind in a way this client cannot honour;
        // the client answers UI_READY(state=4) instead of dropping the frame.
        return BindMode.fromIdOrUnknown(id);
    }

    private static RenderMode renderMode(int id) {
        return RenderMode.fromIdOrUnknown(id);
    }

    private static void requireFullyConsumed(ByteSource source, String message) {
        if (source.readableBytes() != 0) {
            throw new ProtocolException("Trailing bytes in " + message + ": " + source.readableBytes());
        }
    }
}
