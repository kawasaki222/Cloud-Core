package com.cloudcore.protocol;

public interface ByteSource {
    int readUnsignedByte();

    byte[] readBytes(int length);

    int readUnsignedShort();

    int readInt();

    long readLong();

    int readVarInt();

    long readVarLong();

    int readableBytes();
}
