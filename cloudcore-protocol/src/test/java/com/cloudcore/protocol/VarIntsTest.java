package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VarIntsTest {

    @Test
    void roundTripsIntBoundaries() {
        int[] values = {0, 1, -1, 127, 128, 16_383, 16_384, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int value : values) {
            ArrayByteSink sink = new ArrayByteSink();
            VarInts.write(sink, value);
            ArrayByteSource source = new ArrayByteSource(sink.toByteArray());
            assertEquals(value, source.readVarInt());
            assertEquals(0, source.readableBytes());
        }
    }

    @Test
    void roundTripsLongBoundaries() {
        long[] values = {0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 1L << 40};
        for (long value : values) {
            ArrayByteSink sink = new ArrayByteSink();
            VarInts.writeLong(sink, value);
            ArrayByteSource source = new ArrayByteSource(sink.toByteArray());
            assertEquals(value, source.readVarLong());
            assertEquals(0, source.readableBytes());
        }
    }

    @Test
    void rejectsTooLongInt() {
        byte[] bytes = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01};
        ArrayByteSource source = new ArrayByteSource(bytes);

        assertThrows(ProtocolException.class, source::readVarInt);
    }
}
