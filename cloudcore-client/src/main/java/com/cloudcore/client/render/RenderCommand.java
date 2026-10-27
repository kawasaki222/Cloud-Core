package com.cloudcore.client.render;

/**
 * Backend-independent drawing instructions. Geometry is in logical GUI coordinates.
 */
public sealed interface RenderCommand {

    record FillRect(int x, int y, int width, int height, int argb) implements RenderCommand {
    }

    /**
     * @param font CloudCore-relative font path, or null to keep the vanilla font
     * @param size em size for vector backends; ignored by the vanilla font, which has a
     *             fixed cell height
     */
    record Text(String text, int x, int y, int argb, boolean shadow, String font, float size)
            implements RenderCommand {

        public static final float DEFAULT_SIZE = 9.0f;

        public Text(String text, int x, int y, int argb, boolean shadow) {
            this(text, x, y, argb, shadow, null, DEFAULT_SIZE);
        }

        public Text(String text, int x, int y, int argb, boolean shadow, String font) {
            this(text, x, y, argb, shadow, font, DEFAULT_SIZE);
        }

        public Text {
            if (!(size > 0.0f) || size > 512.0f) {
                size = DEFAULT_SIZE;
            }
        }
    }

    /**
     * @param nineSlice optional {@code [left, top, right, bottom]} borders in source-texture
     *                  pixels; the backends keep them unscaled and stretch the middle
     */
    /**
     * @param animation playback options for GIF sources; ignored for static textures
     */
    record Image(String texture, int x, int y, int width, int height, int[] nineSlice,
            Animation animation) implements RenderCommand {

        public Image(String texture, int x, int y, int width, int height) {
            this(texture, x, y, width, height, null, Animation.DEFAULT);
        }

        public Image(String texture, int x, int y, int width, int height, int[] nineSlice) {
            this(texture, x, y, width, height, nineSlice, Animation.DEFAULT);
        }

        /** {@code animated: off} freezes the first frame; speed is a multiplier. */
        record Animation(boolean enabled, boolean loop, float speed) {
            public static final Animation DEFAULT = new Animation(true, true, 1.0f);

            public Animation {
                if (!(speed > 0.0f) || speed > 4.0f) {
                    speed = 1.0f;
                }
            }
        }

        public Image {
            nineSlice = nineSlice == null || nineSlice.length != 4
                    ? null
                    : nineSlice.clone();
        }

        @Override
        public int[] nineSlice() {
            return nineSlice == null ? null : nineSlice.clone();
        }
    }

    /** Coloured text segments drawn sequentially; used when a label declares text runs. */
    record RichText(java.util.List<Run> runs, int x, int y, boolean shadow, String font,
            float size) implements RenderCommand {

        public RichText(java.util.List<Run> runs, int x, int y, boolean shadow) {
            this(runs, x, y, shadow, null, Text.DEFAULT_SIZE);
        }

        public RichText {
            runs = java.util.List.copyOf(runs);
            if (!(size > 0.0f) || size > 512.0f) {
                size = Text.DEFAULT_SIZE;
            }
        }

        public record Run(String text, int argb) {
            public Run {
                java.util.Objects.requireNonNull(text, "text");
            }
        }
    }

    /** Starts a clipping rectangle; nested clips intersect with their parent. */
    record ClipPush(int x, int y, int width, int height) implements RenderCommand {
    }

    /** Ends the most recent {@link ClipPush}. */
    record ClipPop() implements RenderCommand {
    }

    /**
     * A decorative vanilla item drawn by the native bridge; {@code itemId} is the registry id
     * and {@code count} is only drawn when {@code showCount} is set.
     */
    record ItemIcon(String itemId, int count, boolean showCount, int x, int y, float scale)
            implements RenderCommand {
    }
}
