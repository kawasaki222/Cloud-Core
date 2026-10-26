package com.cloudcore.protocol;

import java.util.Objects;

public final class ArrayByteSource implements ByteSource {
    private final byte[] data;
    private int index;

    public ArrayByteSource(byte[] data) {
        this.data = Objects.requireNonNull(data, "data");
    }

    @Override
    public int readUnsignedByte() {
        require(1);
        return data[index++] & 0xFF;
    }

    @Override
    public byte[] readBytes(int length) {
        if (length < 0) {
            throw new ProtocolException("Negative byte length: " + length);
        }
        require(length);
        byte[] result = new byte[length];
        System.arraycopy(data, index, result, 0, length);
        index += length;
        return result;
    }

    @Override
    public int readUnsignedShort() {
        require(2);
        int value = ((data[index] & 0xFF) << 8) | (data[index + 1] & 0xFF);
        index += 2;
        return value;
    }

    @Override
    public int readInt() {
        require(4);
        int value = ((data[index] & 0xFF) << 24)
                | ((data[index + 1] & 0xFF) << 16)
                | ((data[index + 2] & 0xFF) << 8)
                | (data[index + 3] & 0xFF);
        index += 4;
        return value;
    }

    @Override
    public long readLong() {
        long high = readInt() & 0xFFFFFFFFL;
        long low = readInt() & 0xFFFFFFFFL;
        return (high << 32) | low;
    }

    @Override
    public int readVarInt() {
        return VarInts.read(this);
    }

    @Override
    public long readVarLong() {
        return VarInts.readLong(this);
    }

    @Override
    public int readableBytes() {
        return data.length - index;
    }

    private void require(int count) {
        if (count > readableBytes()) {
            throw new ProtocolException("Truncated input: need " + count + " bytes, have " + readableBytes());
        }
    }
}
