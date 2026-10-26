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
    private final java.util.ArrayList<int[]> clips = new java.util.ArrayList<>();
    private int[] activeClip;

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
                case RenderCommand.RichText rich -> drawRichText(frame, rich);
                case RenderCommand.ClipPush clip -> pushClip(frame, clip);
                case RenderCommand.ClipPop ignored -> popClip(frame);
            }
        }
    }

    private static void drawRichText(FrameContext frame, RenderCommand.RichText rich) {
        int cursor = rich.x();
        for (RenderCommand.RichText.Run run : rich.runs()) {
            frame.graphics().drawString(Minecraft.getInstance().font, run.text(), cursor,
                    rich.y(), run.argb(), rich.shadow());
            cursor += Minecraft.getInstance().font.width(run.text());
        }
    }

    private static void drawImage(FrameContext frame, RenderCommand.Image image) {
        Identifier texture = TextureReferences.resolve(image.texture(), image.animation());
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
        for (NineSlice.Piece piece : NineSlice.split(image.x(), image.y(),
                image.width(), image.height(), textureWidth, textureHeight,
                image.nineSlice())) {
            if (piece.dstWidth() <= 0 || piece.dstHeight() <= 0) {
                continue;
            }
            frame.graphics().blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    texture, piece.dstX(), piece.dstY(), piece.srcX(), piece.srcY(),
                    piece.dstWidth(), piece.dstHeight(),
                    piece.srcWidth(), piece.srcHeight(), textureWidth, textureHeight);
        }
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

    private void pushClip(FrameContext frame, RenderCommand.ClipPush clip) {
        int[] rect = {clip.x(), clip.y(), clip.x() + clip.width(), clip.y() + clip.height()};
        if (activeClip != null) {
            rect[0] = Math.max(rect[0], activeClip[0]);
            rect[1] = Math.max(rect[1], activeClip[1]);
            rect[2] = Math.min(rect[2], activeClip[2]);
            rect[3] = Math.min(rect[3], activeClip[3]);
        }
        clips.add(activeClip);
        activeClip = rect;
        applyClip(frame);
    }

    private void popClip(FrameContext frame) {
        activeClip = clips.isEmpty() ? null : clips.remove(clips.size() - 1);
        applyClip(frame);
    }

    private void applyClip(FrameContext frame) {
        if (activeClip == null) {
            frame.graphics().disableScissor();
            return;
        }
        int x1 = activeClip[0];
        int y1 = activeClip[1];
        int x2 = Math.max(x1, activeClip[2]);
        int y2 = Math.max(y1, activeClip[3]);
        frame.graphics().enableScissor(x1, y1, x2, y2);
    }

    @Override
    public void endFrame() {
        if (context != null && (activeClip != null || !clips.isEmpty())) {
            context.graphics().disableScissor();
        }
        clips.clear();
        activeClip = null;
        context = null;
    }
}
