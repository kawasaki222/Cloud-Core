package com.cloudcore.protocol;

public final class Opcode {
    private Opcode() {
    }

    // Server -> client
    public static final int HELLO_ACK = 0x01;
    public static final int UI_OPEN = 0x10;
    public static final int UI_CLOSE = 0x11;
    public static final int UI_DEFINITION = 0x12;
    public static final int ELEMENT_UPDATE = 0x14;
    public static final int EVENT_RESULT = 0x15;
    public static final int NOTIFICATION = 0x16;
    public static final int DIAGNOSTIC = 0x17;
    public static final int TEXTURE_PUT = 0x28;
    public static final int TEXTURE_RELEASE = 0x29;
    public static final int THEME_SET = 0x2A;
    public static final int TEXTURE_REF = 0x2B;
    public static final int PING = 0x30;
    public static final int SERVER_CONFIG = 0x31;

    // Client -> server
    public static final int HELLO = 0x80;
    public static final int UI_READY = 0x81;
    public static final int EVENT = 0x82;
    public static final int UI_CLOSED = 0x83;
    public static final int TEXTURE_REQUEST = 0x84;
    public static final int PONG = 0x85;
    public static final int CLIENT_ERROR = 0x86;
    public static final int METRICS = 0x87;
    public static final int SCREEN_PROBE = 0x88;

    public static boolean isServerbound(int opcode) {
        return (opcode & 0x80) != 0;
    }
}
