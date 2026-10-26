package com.cloudcore.protocol;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Typed value used by event payloads and element updates.
 */
public sealed interface WireValue permits
        WireValue.NullValue,
        WireValue.BoolValue,
        WireValue.IntValue,
        WireValue.LongValue,
        WireValue.FloatValue,
        WireValue.DoubleValue,
        WireValue.StringValue,
        WireValue.ListValue,
        WireValue.MapValue {

    record NullValue() implements WireValue {
        public static final NullValue INSTANCE = new NullValue();
    }

    record BoolValue(boolean value) implements WireValue {
    }

    record IntValue(int value) implements WireValue {
    }

    record LongValue(long value) implements WireValue {
    }

    record FloatValue(float value) implements WireValue {
    }

    record DoubleValue(double value) implements WireValue {
    }

    record StringValue(String value) implements WireValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record ListValue(List<WireValue> values) implements WireValue {
        public ListValue {
            values = List.copyOf(values);
        }
    }

    record MapValue(Map<String, WireValue> values) implements WireValue {
        public MapValue {
            values = Map.copyOf(values);
        }
    }
}
