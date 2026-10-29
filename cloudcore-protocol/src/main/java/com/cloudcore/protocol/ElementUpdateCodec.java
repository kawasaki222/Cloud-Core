package com.cloudcore.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Wire codec for {@code ELEMENT_UPDATE} (0x14). */
public final class ElementUpdateCodec {

    private ElementUpdateCodec() {
    }

    public static byte[] encode(ElementUpdate update, Limits limits) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSink sink = new ArrayByteSink();
        ProtocolText.write(sink, update.uiId(), limits);
        sink.writeVarInt(update.revision());
        List<ElementUpdate.Entry> entries = update.entries();
        if (entries.size() > limits.maxWireEntries()) {
            throw new ProtocolException("ELEMENT_UPDATE entries exceed limit: "
                    + entries.size());
        }
        sink.writeVarInt(entries.size());
        for (ElementUpdate.Entry entry : entries) {
            ProtocolText.write(sink, entry.elementId(), limits);
            sink.writeByte(entry.propertyId());
            WireValueCodec.write(sink, entry.value(), limits);
        }
        byte[] body = sink.toByteArray();
        if (body.length > limits.maxDecompressedBytes()) {
            throw new ProtocolException("ELEMENT_UPDATE body exceeds limit: " + body.length);
        }
        return body;
    }

    public static ElementUpdate decode(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        if (data.length > limits.maxDecompressedBytes()) {
            throw new ProtocolException("ELEMENT_UPDATE body exceeds limit: " + data.length);
        }
        ArrayByteSource source = new ArrayByteSource(data);
        String uiId = ProtocolText.read(source, limits);
        int revision = source.readVarInt();
        if (revision < 0) {
            throw new ProtocolException("Invalid revision: " + revision);
        }
        int count = source.readVarInt();
        if (count < 0 || count > limits.maxWireEntries()) {
            throw new ProtocolException("Invalid element update count: " + count);
        }
        List<ElementUpdate.Entry> entries = new ArrayList<>(Math.min(count, 64));
        for (int index = 0; index < count; index++) {
            String elementId = ProtocolText.read(source, limits);
            int propertyId = source.readUnsignedByte();
            WireValue value = WireValueCodec.read(source, limits);
            entries.add(new ElementUpdate.Entry(elementId, propertyId, value));
        }
        if (source.readableBytes() != 0) {
            throw new ProtocolException("Trailing bytes in ELEMENT_UPDATE: "
                    + source.readableBytes());
        }
        return new ElementUpdate(uiId, revision, entries);
    }
}
