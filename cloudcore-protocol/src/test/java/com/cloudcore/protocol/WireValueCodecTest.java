package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WireValueCodecTest {

    @Test
    void roundTripsNestedValue() {
        WireValue value = new WireValue.MapValue(Map.of(
                "name", new WireValue.StringValue("Nono"),
                "count", new WireValue.IntValue(-42),
                "long", new WireValue.LongValue(9_000_000_000L),
                "ratio", new WireValue.FloatValue(0.5f),
                "precise", new WireValue.DoubleValue(Math.PI),
                "enabled", new WireValue.BoolValue(true),
                "nothing", WireValue.NullValue.INSTANCE,
                "items", new WireValue.ListValue(List.of(
                        new WireValue.StringValue("a"),
                        new WireValue.IntValue(1)))));

        byte[] encoded = WireValueCodec.encode(value, Limits.S2C);
        assertEquals(value, WireValueCodec.decode(encoded, Limits.S2C));
    }

    @Test
    void rejectsUnknownTag() {
        byte[] encoded = {0x7F};

        assertThrows(ProtocolException.class, () -> WireValueCodec.decode(encoded, Limits.S2C));
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] encoded = WireValueCodec.encode(new WireValue.IntValue(1), Limits.S2C);
        byte[] withTrailing = new byte[encoded.length + 1];
        System.arraycopy(encoded, 0, withTrailing, 0, encoded.length);
        withTrailing[encoded.length] = 0x01;

        assertThrows(ProtocolException.class, () -> WireValueCodec.decode(withTrailing, Limits.S2C));
    }

    @Test
    void rejectsDepthLimit() {
        Limits limits = new Limits(1_048_576, 1_048_576, 24_000, 32_767, 1, 4_096);
        WireValue value = new WireValue.ListValue(List.of(
                new WireValue.ListValue(List.of(
                        new WireValue.ListValue(List.of())))));

        assertThrows(ProtocolException.class, () -> WireValueCodec.encode(value, limits));
    }

    @Test
    void rejectsStringLimit() {
        Limits limits = new Limits(1_048_576, 1_048_576, 24_000, 2, 16, 4_096);
        WireValue value = new WireValue.StringValue("abc");

        assertThrows(ProtocolException.class, () -> WireValueCodec.encode(value, limits));
    }

    @Test
    void rejectsInvalidBoolean() {
        byte[] encoded = {0x01, 0x02};

        assertThrows(ProtocolException.class, () -> WireValueCodec.decode(encoded, Limits.S2C));
    }
}
