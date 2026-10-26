package com.cloudcore.protocol;

public final class VarInts {
    private VarInts() {
    }

    public static void write(ByteSink sink, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            sink.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        sink.writeByte(remaining);
    }

    public static int read(ByteSource source) {
        int value = 0;
        int position = 0;
        while (true) {
            int current = source.readUnsignedByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 35) {
                throw new ProtocolException("VarInt too long");
            }
        }
    }

    public static void writeLong(ByteSink sink, long value) {
        long remaining = value;
        while ((remaining & ~0x7FL) != 0) {
            sink.writeByte(((int) remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        sink.writeByte((int) remaining);
    }

    public static long readLong(ByteSource source) {
        long value = 0;
        int position = 0;
        while (true) {
            int current = source.readUnsignedByte();
            value |= (long) (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 70) {
                throw new ProtocolException("VarLong too long");
            }
        }
    }
}
