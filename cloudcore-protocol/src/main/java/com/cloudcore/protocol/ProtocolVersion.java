package com.cloudcore.protocol;

/**
 * Protocol version carried by the handshake.
 *
 * <p>The envelope version is the major version. Minor versions only add fields, opcodes,
 * feature bits or values at the end of existing bodies.
 */
public final class ProtocolVersion {
    public static final int MAJOR = 1;
    public static final int MINOR = 1;

    private ProtocolVersion() {
    }

    public static boolean isCompatible(int major) {
        return major == MAJOR;
    }
}
