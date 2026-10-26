package com.cloudcore.client.render;

import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Complete native fallback. It draws the subset of the render list that does not require
 * a vector context; items, slots, EditBox text and tooltip content remain native by design.
 */
public final class NativeBackend implements RenderBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");

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
                case RenderCommand.Image image -> drawImage(frame, image);
                case RenderCommand.ItemIcon icon -> drawItem(frame, icon);
            }
        }
    }

    private static void drawImage(FrameContext frame, RenderCommand.Image image) {
        Identifier texture = TextureReferences.resolve(image.texture());
        if (texture == null) {
            return;
        }
        // 1.21.11 blits take the texture size explicitly; the legacy normalized-UV overload
        // draws nothing for un-atlased textures.
        net.minecraft.client.renderer.texture.AbstractTexture abstractTexture =
                Minecraft.getInstance().getTextureManager().getTexture(texture);
        com.mojang.blaze3d.textures.GpuTexture gpuTexture =
                abstractTexture == null ? null : abstractTexture.getTexture();
        int textureWidth = gpuTexture == null ? 0 : gpuTexture.getWidth(0);
        int textureHeight = gpuTexture == null ? 0 : gpuTexture.getHeight(0);
        if (textureWidth <= 0 || textureHeight <= 0) {
            LOGGER.warn("[CC-RENDER-002] texture has no GPU image: {}", image.texture());
            return;
        }
        frame.graphics().blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                texture, image.x(), image.y(), 0.0f, 0.0f,
                image.width(), image.height(), textureWidth, textureHeight);
    }

    private static void drawItem(FrameContext frame, RenderCommand.ItemIcon icon) {
        Identifier identifier = Identifier.tryParse(icon.itemId());
        Item item = identifier == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(identifier);
        if (item == null || item == Items.AIR) {
            LOGGER.warn("[CC-RENDER-001] unknown item_icon id: {}", icon.itemId());
            return;
        }
        ItemStack stack = new ItemStack(item, Math.max(1, icon.count()));
        float scale = icon.scale();
        if (scale == 1.0f) {
            frame.graphics().renderItem(stack, icon.x(), icon.y());
        } else {
            Matrix3x2fStack pose = frame.graphics().pose();
            pose.pushMatrix();
            pose.translate((float) icon.x(), (float) icon.y());
            pose.scale(scale, scale);
            frame.graphics().renderItem(stack, 0, 0);
            pose.popMatrix();
        }
        if (icon.showCount() && icon.count() > 1) {
            frame.graphics().renderItemDecorations(Minecraft.getInstance().font, stack,
                    icon.x(), icon.y());
        }
    }

    @Override
    public void endFrame() {
        context = null;
    }
}
