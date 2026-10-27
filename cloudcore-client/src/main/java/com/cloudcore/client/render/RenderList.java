package com.cloudcore.client.render;

import java.util.List;
import java.util.Objects;

public record RenderList(List<RenderCommand> commands) {

    public static final RenderList EMPTY = new RenderList(List.of());

    public RenderList {
        Objects.requireNonNull(commands, "commands");
        commands = List.copyOf(commands);
    }

    public boolean isEmpty() {
        return commands.isEmpty();
    }

    /**
     * The subset the native backend must draw after the vector backend ran: text and item
     * icons, which NanoVG does not render.
     */
    public RenderList nativeOverlay() {
        return nativeOverlay(true);
    }

    /**
     * @param includeText false when NanoVG loaded a font and already drew the text
     */
    public RenderList nativeOverlay(boolean includeText) {
        return nativeOverlay(new VectorTextSupport() {
            @Override
            public boolean drawsText(RenderCommand.Text text) {
                return !includeText;
            }

            @Override
            public boolean drawsRichText(RenderCommand.RichText text) {
                return !includeText;
            }
        });
    }

    /**
     * Text is kept for the native backend whenever the predicate reports that the vector
     * backend did not draw it, so a document with custom fonts mixes both paths.
     */
    public RenderList nativeOverlay(VectorTextSupport vectorText) {
        java.util.List<RenderCommand> overlay = new java.util.ArrayList<>();
        for (RenderCommand command : commands) {
            if (command instanceof RenderCommand.Text text) {
                if (vectorText.drawsText(text)) {
                    continue;
                }
            } else if (command instanceof RenderCommand.RichText rich) {
                if (vectorText.drawsRichText(rich)) {
                    continue;
                }
            } else if (!(command instanceof RenderCommand.ItemIcon)
                    && !isNativeImage(command)) {
                continue;
            }
            overlay.add(command);
        }
        return overlay.isEmpty() ? EMPTY : new RenderList(overlay);
    }

    /** Reports which text a vector backend already drew itself. */
    public interface VectorTextSupport {
        boolean drawsText(RenderCommand.Text text);

        boolean drawsRichText(RenderCommand.RichText text);
    }

    /** Generated textures have no file bytes for NanoVG, so the native backend draws them. */
    private static boolean isNativeImage(RenderCommand command) {
        return command instanceof RenderCommand.Image image
                && image.texture().startsWith("builtin:");
    }
}
