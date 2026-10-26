package com.cloudcore.client.render;

/**
 * Backend-independent drawing instructions. Geometry is in logical GUI coordinates.
 */
public sealed interface RenderCommand {

    record FillRect(int x, int y, int width, int height, int argb) implements RenderCommand {
    }

    record Text(String text, int x, int y, int argb, boolean shadow, String font)
            implements RenderCommand {

        public Text(String text, int x, int y, int argb, boolean shadow) {
            this(text, x, y, argb, shadow, null);
        }
    }

    /**
     * @param nineSlice optional {@code [left, top, right, bottom]} borders in source-texture
     *                  pixels; the backends keep them unscaled and stretch the middle
     */
    record Image(String texture, int x, int y, int width, int height, int[] nineSlice)
            implements RenderCommand {

        public Image(String texture, int x, int y, int width, int height) {
            this(texture, x, y, width, height, null);
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
    record RichText(java.util.List<Run> runs, int x, int y, boolean shadow)
            implements RenderCommand {

        public RichText {
            runs = java.util.List.copyOf(runs);
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
