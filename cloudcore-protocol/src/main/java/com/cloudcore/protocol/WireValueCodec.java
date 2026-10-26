package com.cloudcore.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WireValueCodec {
    private static final int TAG_NULL = 0x00;
    private static final int TAG_BOOL = 0x01;
    private static final int TAG_INT = 0x02;
    private static final int TAG_LONG = 0x03;
    private static final int TAG_FLOAT = 0x04;
    private static final int TAG_DOUBLE = 0x05;
    private static final int TAG_STRING = 0x06;
    private static final int TAG_LIST = 0x07;
    private static final int TAG_MAP = 0x08;

    private WireValueCodec() {
    }

    public static byte[] encode(WireValue value, Limits limits) {
        ArrayByteSink sink = new ArrayByteSink();
        write(sink, value, limits);
        return sink.toByteArray();
    }

    public static void write(ByteSink sink, WireValue value, Limits limits) {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(limits, "limits");
        writeValue(sink, value, limits, 0, new int[]{0});
    }

    public static WireValue decode(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        ArrayByteSource source = new ArrayByteSource(data);
        WireValue value = read(source, limits);
        if (source.readableBytes() != 0) {
            throw new ProtocolException("Trailing bytes after WireValue: " + source.readableBytes());
        }
        return value;
    }

    public static WireValue read(ByteSource source, Limits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        return readValue(source, limits, 0, new int[]{0});
    }

    private static void writeValue(ByteSink sink, WireValue value, Limits limits, int depth, int[] entries) {
        if (depth > limits.maxWireDepth()) {
            throw new ProtocolException("WireValue depth exceeds limit: " + depth);
        }
        entries[0]++;
        if (entries[0] > limits.maxWireEntries()) {
            throw new ProtocolException("WireValue entries exceed limit: " + entries[0]);
        }

        switch (value) {
            case WireValue.NullValue ignored -> sink.writeByte(TAG_NULL);
            case WireValue.BoolValue bool -> {
                sink.writeByte(TAG_BOOL);
                sink.writeByte(bool.value() ? 1 : 0);
            }
            case WireValue.IntValue integer -> {
                sink.writeByte(TAG_INT);
                sink.writeVarInt(zigZag(integer.value()));
            }
            case WireValue.LongValue longValue -> {
                sink.writeByte(TAG_LONG);
                sink.writeVarLong(zigZag(longValue.value()));
            }
            case WireValue.FloatValue floatValue -> {
                sink.writeByte(TAG_FLOAT);
                sink.writeInt(Float.floatToIntBits(floatValue.value()));
            }
            case WireValue.DoubleValue doubleValue -> {
                sink.writeByte(TAG_DOUBLE);
                sink.writeLong(Double.doubleToLongBits(doubleValue.value()));
            }
            case WireValue.StringValue stringValue -> {
                sink.writeByte(TAG_STRING);
                writeString(sink, stringValue.value(), limits);
            }
            case WireValue.ListValue listValue -> {
                List<WireValue> values = listValue.values();
                if (values.size() > limits.maxWireEntries()) {
                    throw new ProtocolException("List too large: " + values.size());
                }
                sink.writeByte(TAG_LIST);
                sink.writeVarInt(values.size());
                for (WireValue child : values) {
                    writeValue(sink, child, limits, depth + 1, entries);
                }
            }
            case WireValue.MapValue mapValue -> {
                Map<String, WireValue> values = mapValue.values();
                if (values.size() > limits.maxWireEntries()) {
                    throw new ProtocolException("Map too large: " + values.size());
                }
                sink.writeByte(TAG_MAP);
                sink.writeVarInt(values.size());
                for (Map.Entry<String, WireValue> entry : values.entrySet()) {
                    writeString(sink, entry.getKey(), limits);
                    writeValue(sink, entry.getValue(), limits, depth + 1, entries);
                }
            }
        }
    }

    private static WireValue readValue(ByteSource source, Limits limits, int depth, int[] entries) {
        if (depth > limits.maxWireDepth()) {
            throw new ProtocolException("WireValue depth exceeds limit: " + depth);
        }
        entries[0]++;
        if (entries[0] > limits.maxWireEntries()) {
            throw new ProtocolException("WireValue entries exceed limit: " + entries[0]);
        }

        int tag = source.readUnsignedByte();
        return switch (tag) {
            case TAG_NULL -> WireValue.NullValue.INSTANCE;
            case TAG_BOOL -> {
                int raw = source.readUnsignedByte();
                if (raw != 0 && raw != 1) {
                    throw new ProtocolException("Invalid boolean: " + raw);
                }
                yield new WireValue.BoolValue(raw == 1);
            }
            case TAG_INT -> new WireValue.IntValue(unZigZag(source.readVarInt()));
            case TAG_LONG -> new WireValue.LongValue(unZigZag(source.readVarLong()));
            case TAG_FLOAT -> new WireValue.FloatValue(Float.intBitsToFloat(source.readInt()));
            case TAG_DOUBLE -> new WireValue.DoubleValue(Double.longBitsToDouble(source.readLong()));
            case TAG_STRING -> new WireValue.StringValue(readString(source, limits));
            case TAG_LIST -> {
                int count = source.readVarInt();
                if (count < 0 || count > limits.maxWireEntries()) {
                    throw new ProtocolException("Invalid list size: " + count);
                }
                List<WireValue> values = new ArrayList<>(Math.min(count, 64));
                for (int i = 0; i < count; i++) {
                    values.add(readValue(source, limits, depth + 1, entries));
                }
                yield new WireValue.ListValue(values);
            }
            case TAG_MAP -> {
                int count = source.readVarInt();
                if (count < 0 || count > limits.maxWireEntries()) {
                    throw new ProtocolException("Invalid map size: " + count);
                }
                Map<String, WireValue> values = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    String key = readString(source, limits);
                    if (values.put(key, readValue(source, limits, depth + 1, entries)) != null) {
                        throw new ProtocolException("Duplicate map key: " + key);
                    }
                }
                yield new WireValue.MapValue(values);
            }
            default -> throw new ProtocolException("Unknown WireValue tag: 0x" + Integer.toHexString(tag));
        };
    }

    private static void writeString(ByteSink sink, String value, Limits limits) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limits.maxStringBytes()) {
            throw new ProtocolException("String too long: " + bytes.length);
        }
        sink.writeVarInt(bytes.length);
        sink.writeBytes(bytes);
    }

    private static String readString(ByteSource source, Limits limits) {
        int length = source.readVarInt();
        if (length < 0 || length > limits.maxStringBytes()) {
            throw new ProtocolException("Invalid string length: " + length);
        }
        return new String(source.readBytes(length), StandardCharsets.UTF_8);
    }

    private static int zigZag(int value) {
        return (value << 1) ^ (value >> 31);
    }

    private static int unZigZag(int value) {
        return (value >>> 1) ^ -(value & 1);
    }

    private static long zigZag(long value) {
        return (value << 1) ^ (value >> 63);
    }

    private static long unZigZag(long value) {
        return (value >>> 1) ^ -(value & 1);
    }
}
