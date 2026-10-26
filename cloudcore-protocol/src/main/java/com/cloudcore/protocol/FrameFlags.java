package com.cloudcore.protocol;

public final class FrameFlags {
    private FrameFlags() {
    }

    public static final int COMPRESSED = 1;
    public static final int FRAGMENT = 1 << 1;
    public static final int KNOWN_MASK = COMPRESSED | FRAGMENT;
}
