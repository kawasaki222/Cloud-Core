package com.cloudcore.protocol;

public interface ByteSink {
    void writeByte(int value);

    void writeBytes(byte[] value);

    void writeShort(int value);

    void writeInt(int value);

    void writeLong(long value);

    void writeVarInt(int value);

    void writeVarLong(long value);
}
