package com.cloudcore.client.render;

import java.util.Objects;
import net.minecraft.client.Minecraft;

/**
 * Complete native fallback. It draws the subset of the render list that does not require
 * a vector context; items, slots, EditBox text and tooltip content remain native by design.
 */
public final class NativeBackend implements RenderBackend {
    private FrameContext context;

    @Override
    public BackendId id() {
        return BackendId.NATIVE;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public void beginFrame(FrameContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public void draw(RenderList list, Layer layer) {
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(layer, "layer");
        FrameContext frame = context;
        if (frame == null) {
            throw new IllegalStateException("beginFrame must be called before draw");
        }
        for (RenderCommand command : list.commands()) {
            switch (command) {
                case RenderCommand.FillRect rect -> frame.graphics().fill(
                        rect.x(), rect.y(), rect.x() + rect.width(), rect.y() + rect.height(),
                        rect.argb());
                case RenderCommand.Text text -> frame.graphics().drawString(
                        Minecraft.getInstance().font, text.text(), text.x(), text.y(),
                        text.argb(), text.shadow());
                case RenderCommand.Image image -> {
                    net.minecraft.resources.Identifier texture =
                            LocalTextureRegistry.resolveLocal(image.texture());
                    if (texture != null) {
                        frame.graphics().blit(texture, image.x(), image.y(),
                                image.width(), image.height(), 0.0f, 0.0f, 1.0f, 1.0f);
                    }
                }
            }
        }
    }

    @Override
    public void endFrame() {
        context = null;
    }
}
