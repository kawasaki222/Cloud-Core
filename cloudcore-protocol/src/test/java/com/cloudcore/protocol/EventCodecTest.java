package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EventCodecTest {

    @Test
    void roundTripsClickEventWithPayload() {
        UiEvent event = new UiEvent(7, "shop", "rows[2].row", 42,
                Map.of("index", new WireValue.IntValue(2),
                        "shift", new WireValue.BoolValue(true),
                        "value", new WireValue.StringValue("sword")));

        UiEvent decoded = EventCodec.decode(EventCodec.encode(event, Limits.C2S), Limits.C2S);

        assertEquals(event, decoded);
    }

    @Test
    void rejectsNonMapPayload() {
        byte[] encoded = EventCodec.encode(
                new UiEvent(1, "ui", "el", 0, Map.of()), Limits.C2S);
        // Corrupt the payload tag: EMPTY map is 0x08 0x00, flip it to a NULL value.
        encoded[encoded.length - 2] = 0x00;
        encoded[encoded.length - 1] = 0x00;
        assertThrows(ProtocolException.class, () -> EventCodec.decode(encoded, Limits.C2S));
    }
}
