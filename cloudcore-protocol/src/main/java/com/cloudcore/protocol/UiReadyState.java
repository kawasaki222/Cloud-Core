package com.cloudcore.protocol;

public final class UiReadyState {
    public static final int OK = 0;
    public static final int DOCUMENT_MISSING = 1;
    public static final int DOCUMENT_INVALID = 2;
    public static final int BIND_FAILED = 3;
    public static final int SURFACE_UNSUPPORTED = 4;

    private UiReadyState() {
    }
}
