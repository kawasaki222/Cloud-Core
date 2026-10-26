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
}
