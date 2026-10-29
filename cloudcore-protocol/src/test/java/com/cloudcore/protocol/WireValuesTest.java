package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WireValuesTest {

    @Test
    void convertsDocumentAttributeTypes() {
        assertEquals(new WireValue.BoolValue(true), WireValues.toWire(true).orElseThrow());
        assertEquals(new WireValue.IntValue(7), WireValues.toWire(7).orElseThrow());
        assertEquals(new WireValue.LongValue(7L), WireValues.toWire(7L).orElseThrow());
        assertEquals(new WireValue.FloatValue(0.5f), WireValues.toWire(0.5f).orElseThrow());
        assertEquals(new WireValue.DoubleValue(0.5), WireValues.toWire(0.5).orElseThrow());
        assertEquals(new WireValue.StringValue("x"), WireValues.toWire("x").orElseThrow());
        assertEquals(WireValue.NullValue.INSTANCE, WireValues.toWire(null).orElseThrow());
    }

    @Test
    void convertsNestedMapsAndLists() {
        WireValue value = WireValues.toWire(Map.of(
                "runs", List.of(Map.of("text", "hi", "color", "#FFFFFFFF")))).orElseThrow();

        WireValue.MapValue map = (WireValue.MapValue) value;
        WireValue.ListValue runs = (WireValue.ListValue) map.values().get("runs");
        WireValue.MapValue run = (WireValue.MapValue) runs.values().get(0);
        assertEquals("hi", ((WireValue.StringValue) run.values().get("text")).value());
    }

    @Test
    void rejectsValuesWithoutAWireType() {
        assertTrue(WireValues.toWire(new Object()).isEmpty());
        assertTrue(WireValues.toWire(Map.of(1, "x")).isEmpty());
    }

    @Test
    void fromWireRebuildsAttributes() {
        WireValue value = new WireValue.MapValue(Map.of(
                "visible", new WireValue.BoolValue(true),
                "value", new WireValue.DoubleValue(0.75),
                "text", new WireValue.StringValue("hi"),
                "runs", new WireValue.ListValue(List.of(
                        new WireValue.MapValue(Map.of("text",
                                new WireValue.StringValue("hi")))))));

        Map<?, ?> attributes = (Map<?, ?>) WireValues.fromWire(value);

        assertEquals(true, attributes.get("visible"));
        assertEquals(0.75, attributes.get("value"));
        assertEquals("hi", attributes.get("text"));
        assertTrue(attributes.get("runs") instanceof List<?>);
    }
}
