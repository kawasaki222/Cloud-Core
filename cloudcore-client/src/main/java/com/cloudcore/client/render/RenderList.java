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
        java.util.List<RenderCommand> overlay = new java.util.ArrayList<>();
        for (RenderCommand command : commands) {
            if (command instanceof RenderCommand.Text
                    || command instanceof RenderCommand.ItemIcon
                    || isNativeImage(command)) {
                overlay.add(command);
            }
        }
        return overlay.isEmpty() ? EMPTY : new RenderList(overlay);
    }

    /** Generated textures have no file bytes for NanoVG, so the native backend draws them. */
    private static boolean isNativeImage(RenderCommand command) {
        return command instanceof RenderCommand.Image image
                && image.texture().startsWith("builtin:");
    }
}
