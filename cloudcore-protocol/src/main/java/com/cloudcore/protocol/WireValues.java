package com.cloudcore.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Converts document attribute values to and from {@link WireValue}.
 *
 * <p>{@link #toWire(Object)} returns empty for values the wire has no lossless type for; the
 * caller then falls back to a full document instead of silently changing the first value to a
 * string.
 */
public final class WireValues {

    private WireValues() {
    }

    public static Optional<WireValue> toWire(Object value) {
        if (value == null) {
            return Optional.of(WireValue.NullValue.INSTANCE);
        }
        if (value instanceof WireValue already) {
            return Optional.of(already);
        }
        if (value instanceof Boolean flag) {
            return Optional.of(new WireValue.BoolValue(flag));
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return Optional.of(new WireValue.IntValue(((Number) value).intValue()));
        }
        if (value instanceof Long number) {
            return Optional.of(new WireValue.LongValue(number));
        }
        if (value instanceof Float number) {
            return Optional.of(new WireValue.FloatValue(number));
        }
        if (value instanceof Double number) {
            return Optional.of(new WireValue.DoubleValue(number));
        }
        if (value instanceof String text) {
            return Optional.of(new WireValue.StringValue(text));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, WireValue> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    return Optional.empty();
                }
                Optional<WireValue> child = toWire(entry.getValue());
                if (child.isEmpty()) {
                    return Optional.empty();
                }
                converted.put(key, child.get());
            }
            return Optional.of(new WireValue.MapValue(converted));
        }
        if (value instanceof Iterable<?> iterable) {
            List<WireValue> converted = new ArrayList<>();
            for (Object child : iterable) {
                Optional<WireValue> wire = toWire(child);
                if (wire.isEmpty()) {
                    return Optional.empty();
                }
                converted.add(wire.get());
            }
            return Optional.of(new WireValue.ListValue(converted));
        }
        return Optional.empty();
    }

    /** Plain Java value used by document attributes; maps stay insertion-ordered. */
    public static Object fromWire(WireValue value) {
        return switch (value) {
            case WireValue.NullValue ignored -> null;
            case WireValue.BoolValue flag -> flag.value();
            case WireValue.IntValue number -> number.value();
            case WireValue.LongValue number -> number.value();
            case WireValue.FloatValue number -> number.value();
            case WireValue.DoubleValue number -> number.value();
            case WireValue.StringValue text -> text.value();
            case WireValue.ListValue list -> {
                List<Object> values = new ArrayList<>(list.values().size());
                for (WireValue child : list.values()) {
                    values.add(fromWire(child));
                }
                yield values;
            }
            case WireValue.MapValue map -> {
                Map<String, Object> values = new LinkedHashMap<>();
                for (Map.Entry<String, WireValue> entry : map.values().entrySet()) {
                    values.put(entry.getKey(), fromWire(entry.getValue()));
                }
                yield values;
            }
        };
    }
}
