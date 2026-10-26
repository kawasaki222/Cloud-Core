package com.cloudcore.protocol;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

public final class ArrayByteSink implements ByteSink {
    private final ByteArrayOutputStream out;

    public ArrayByteSink() {
        this(new ByteArrayOutputStream());
    }

    public ArrayByteSink(ByteArrayOutputStream out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public void writeByte(int value) {
        out.write(value & 0xFF);
    }

    @Override
    public void writeBytes(byte[] value) {
        Objects.requireNonNull(value, "value");
        out.write(value, 0, value.length);
    }

    @Override
    public void writeShort(int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    @Override
    public void writeInt(int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    @Override
    public void writeLong(long value) {
        writeInt((int) (value >>> 32));
        writeInt((int) value);
    }

    @Override
    public void writeVarInt(int value) {
        VarInts.write(this, value);
    }

    @Override
    public void writeVarLong(long value) {
        VarInts.writeLong(this, value);
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }

    public int size() {
        return out.size();
    }
}
