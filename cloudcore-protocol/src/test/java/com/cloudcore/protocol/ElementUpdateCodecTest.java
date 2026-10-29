package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ElementUpdateCodecTest {

    @Test
    void goldenBytes() {
        ElementUpdate update = new ElementUpdate("u", 2, List.of(
                new ElementUpdate.Entry("a", ElementUpdate.PROPERTY_TEXT,
                        new WireValue.StringValue("x"))));

        byte[] encoded = ElementUpdateCodec.encode(update, Limits.S2C);

        assertEquals("01750201016101060178", hex(encoded));
    }

    @Test
    void roundTripsEveryPropertyShape() {
        ElementUpdate update = new ElementUpdate("shop", 7, List.of(
                new ElementUpdate.Entry("label", ElementUpdate.PROPERTY_TEXT,
                        new WireValue.StringValue("gold")),
                new ElementUpdate.Entry("panel", ElementUpdate.PROPERTY_VISIBLE,
                        new WireValue.BoolValue(true)),
                new ElementUpdate.Entry("bar", ElementUpdate.PROPERTY_VALUE,
                        new WireValue.DoubleValue(0.25)),
                new ElementUpdate.Entry("style", ElementUpdate.PROPERTY_STYLE,
                        new WireValue.MapValue(Map.of("background",
                                new WireValue.StringValue("#FF000000")))),
                new ElementUpdate.Entry("style", ElementUpdate.PROPERTY_INVALID,
                        new WireValue.NullValue())));

        ElementUpdate decoded = ElementUpdateCodec.decode(
                ElementUpdateCodec.encode(update, Limits.S2C), Limits.S2C);

        assertEquals(update, decoded);
    }

    @Test
    void emptyUpdateIsTheSameDocument() {
        ElementUpdate update = new ElementUpdate("shop", 3, List.of());

        assertEquals(update, ElementUpdateCodec.decode(
                ElementUpdateCodec.encode(update, Limits.S2C), Limits.S2C));
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] valid = ElementUpdateCodec.encode(new ElementUpdate("u", 1, List.of()),
                Limits.S2C);
        byte[] padded = java.util.Arrays.copyOf(valid, valid.length + 1);
        padded[valid.length] = 0x7F;

        assertThrows(ProtocolException.class,
                () -> ElementUpdateCodec.decode(padded, Limits.S2C));
    }

    @Test
    void rejectsTooManyEntries() {
        Limits tiny = new Limits(1024, 1024, 256, 128, 4, 2);
        ElementUpdate update = new ElementUpdate("u", 1, List.of(
                entry("a"), entry("b"), entry("c")));

        assertThrows(ProtocolException.class,
                () -> ElementUpdateCodec.encode(update, tiny));
    }

    private static ElementUpdate.Entry entry(String id) {
        return new ElementUpdate.Entry(id, ElementUpdate.PROPERTY_TEXT,
                new WireValue.StringValue("x"));
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(String.format("%02x", value));
        }
        return text.toString();
    }
}
