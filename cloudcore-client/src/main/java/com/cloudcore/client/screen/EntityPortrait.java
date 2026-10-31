package com.cloudcore.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws a living entity as a small centered portrait, using the 1.21.11 async entity render
 * pipeline. The logic mirrors what the vanilla inventory does for its player preview but keeps
 * the input parameters explicit so a page can draw any entity (a pet, a boss, a mannequin)
 * instead of only the viewer.
 */
final class EntityPortrait {
    private EntityPortrait() {
    }

    /** Draws {@code entity} centred at {@code (x, y)} with the given scale and yaw offset. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void render(GuiGraphics graphics, LivingEntity entity, int x, int y, int size,
            float yRot) {
        Minecraft minecraft = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        EntityRenderer<? super LivingEntity, ? extends EntityRenderState> renderer =
                (EntityRenderer<? super LivingEntity, ? extends EntityRenderState>) (Object)
                        dispatcher.getRenderer(entity);
        EntityRenderState state = renderer.createRenderState(entity, 1.0f);
        state.lightCoords = 15728880;
        state.shadowPieces.clear();
        state.outlineColor = 0;
        if (state instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState living) {
            living.bodyRot = 180.0f + yRot;
            living.yRot = yRot;
            if (living.pose != net.minecraft.world.entity.Pose.FALL_FLYING) {
                living.xRot = 0.0f;
            } else {
                living.xRot = 0.0f;
            }
            living.boundingBoxWidth = 0.6f;
            living.boundingBoxHeight = 1.8f;
            living.scale = 1.0f;
        }

        Quaternionf flip = new Quaternionf().rotateZ(3.1415927f);
        Quaternionf pitch = new Quaternionf().rotateX(0.0f);
        flip.mul(pitch);
        Vector3f offset = new Vector3f(0.0f, state.boundingBoxHeight / 2.0f, 0.0f);
        // The render state is submitted in screen pixels, not through the current pose stack.
        graphics.submitEntityRenderState(state, size, offset, flip, pitch,
                x - size, y - size * 2, x + size, y);
    }
}
