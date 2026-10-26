package com.cloudcore.protocol;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class Deflate {
    private Deflate() {
    }

    public static byte[] compress(byte[] input) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, input.length / 2));
            byte[] buffer = new byte[8_192];
            while (!deflater.finished()) {
                int written = deflater.deflate(buffer);
                out.write(buffer, 0, written);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    public static byte[] decompress(byte[] input, int maxOutput) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(input);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxOutput, 8_192));
            byte[] buffer = new byte[8_192];
            while (!inflater.finished()) {
                int written;
                try {
                    written = inflater.inflate(buffer);
                } catch (DataFormatException exception) {
                    throw new ProtocolException("Invalid compressed frame", exception);
                }
                if (written == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new ProtocolException("Truncated compressed frame");
                    }
                    throw new ProtocolException("Compressed frame produced no output");
                }
                if (out.size() + written > maxOutput) {
                    throw new ProtocolException("Decompressed frame exceeds limit: " + maxOutput);
                }
                out.write(buffer, 0, written);
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }
}
