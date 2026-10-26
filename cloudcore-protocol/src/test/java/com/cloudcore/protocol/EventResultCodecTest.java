package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EventResultCodecTest {

    @Test
    void roundTrips() {
        EventResult result = new EventResult(9, EventResult.UNKNOWN_ELEMENT,
                EventResult.nameOf(EventResult.UNKNOWN_ELEMENT));
        assertEquals(result, EventResultCodec.decode(
                EventResultCodec.encode(result, Limits.S2C), Limits.S2C));
    }

    @Test
    void namesCoverEveryDocumentedStatus() {
        assertEquals("OK", EventResult.nameOf(EventResult.OK));
        assertEquals("RATE_LIMITED", EventResult.nameOf(EventResult.RATE_LIMITED));
        assertEquals("INVALID_PAYLOAD", EventResult.nameOf(EventResult.INVALID_PAYLOAD));
    }
}
