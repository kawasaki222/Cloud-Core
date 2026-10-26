package com.cloudcore.protocol;

/**
 * Feature bits exchanged in {@code HELLO} and {@code HELLO_ACK}.
 *
 * <p>A feature is only used when both sides set it. Bits are stable for protocol 1.x.
 */
public final class FeatureFlags {
    public static final int INLINE_TEXTURE = 1;
    public static final int HUD = 1 << 1;
    public static final int PLAYER_INVENTORY = 1 << 2;
    public static final int TOOLTIP = 1 << 3;
    public static final int TOOLTIP_SCROLL = 1 << 4;
    public static final int BOSS_BAR = 1 << 5;
    public static final int CUSTOM_FONTS = 1 << 6;
    public static final int PAGE_COMPOSITION = 1 << 7;
    public static final int REPEAT_EXPANSION = 1 << 8;
    public static final int ACTION_ENGINE_V2 = 1 << 9;
    public static final int ANIMATED_TEXTURE = 1 << 10;
    public static final int URL_TEXTURE = 1 << 11;
    public static final int PLACEHOLDER_REFRESH = 1 << 12;
    public static final int CLIENT_URL_FETCH = 1 << 13;
    public static final int CLIENT_GIF_DECODE = 1 << 14;
    public static final int NANOVG = 1 << 15;

    public static final int NONE = 0;
    public static final int ALL = 0xFFFF;

    private FeatureFlags() {
    }

    public static boolean has(int features, int flag) {
        return (features & flag) == flag;
    }
}
