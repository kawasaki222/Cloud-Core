package com.cloudcore.protocol;

public final class UiClosedReason {
    public static final int PLAYER = 0;
    public static final int SERVER = 1;
    public static final int CONTAINER_CLOSED = 2;
    public static final int CLIENT_ERROR = 3;
    public static final int REPLACED = 4;

    private UiClosedReason() {
    }
}
