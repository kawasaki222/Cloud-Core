package com.cloudcore.client.surface;

import com.cloudcore.api.document.UiDocument;
import java.util.Objects;
import net.minecraft.client.Minecraft;

/**
 * Per-session client context handed to a surface adapter.
 */
public record UiRuntime(Minecraft minecraft, OpenRequest request, UiDocument document) {

    public UiRuntime {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(document, "document");
    }
}
