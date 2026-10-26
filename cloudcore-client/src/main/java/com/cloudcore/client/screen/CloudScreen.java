package com.cloudcore.client.screen;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.client.render.DocumentRenderListBuilder;
import com.cloudcore.client.render.DocumentRenderer;
import com.cloudcore.client.render.FrameContext;
import com.cloudcore.client.render.RenderList;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Temporary minimal custom screen. It proves the lifecycle and input ownership before the
 * NanoVG render list lands; it deliberately does not pretend to draw document elements.
 */
public final class CloudScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final int BACKGROUND = 0xB0000000;
    private static final int TITLE_COLOR = 0xFFFFFFFF;

    private final UiDocument document;
    private final DocumentRenderer backend = new DocumentRenderer();
    private final Runnable onCloseAction;
    private RenderList cachedList = RenderList.EMPTY;
    private int cachedWidth = -1;
    private int cachedHeight = -1;
    private int cachedTextureGeneration = -1;
    private final java.util.Map<String, Integer> localScroll = new java.util.HashMap<>();
    private static final int SCROLL_STEP = 12;
    private boolean closed;

    public CloudScreen(UiDocument document) {
        this(document, () -> {
        });
    }

    public CloudScreen(UiDocument document, Runnable onCloseAction) {
        super(Component.literal(Objects.requireNonNull(document, "document").title().plainText()));
        this.document = document;
        this.onCloseAction = Objects.requireNonNull(onCloseAction, "onCloseAction");
    }

    public String uiId() {
        return document.id();
    }

    public UiDocument document() {
        return document;
    }

    @Override
    protected void init() {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        boolean nanoVgComposite = com.cloudcore.client.render.NanoVgSpike.composite(
                graphics, 8, height - 72, 64);
        if (Boolean.getBoolean("cloudcore.nanovg.spike")) {
            int controlX = nanoVgComposite ? 80 : 8;
            graphics.fill(controlX, height - 72, controlX + 64, height - 8, 0xFFFF0000);
        }
        if (width != cachedWidth || height != cachedHeight
                || cachedTextureGeneration != com.cloudcore.client.render.RemoteTextureCache
                        .generation()) {
            rebuild();
        }
        backend.render(graphics, cachedList, new FrameContext(graphics, width, height,
                minecraft.getWindow().getGuiScale(), partialTick));
        graphics.drawString(font, title, 8, 8, TITLE_COLOR, true);
    }

    private void rebuild() {
        cachedList = DocumentRenderListBuilder.build(document, width, height,
                id -> localScroll.getOrDefault(id, 0));
        cachedWidth = width;
        cachedHeight = height;
        cachedTextureGeneration = com.cloudcore.client.render.RemoteTextureCache.generation();
        LOGGER.debug("Built render list for ui={} commands={}", document.id(),
                cachedList.commands().size());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        com.cloudcore.client.render.ScrollTargets.Target target =
                com.cloudcore.client.render.ScrollTargets.find(document, width, height,
                        (int) mouseX, (int) mouseY,
                        id -> localScroll.getOrDefault(id, 0));
        if (target == null || scrollY == 0.0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        // Wheel up (positive) scrolls the content back towards its start.
        int next = target.current() - (int) Math.round(scrollY * SCROLL_STEP);
        next = Math.max(0, Math.min(target.maxScroll(), next));
        if (next == target.current()) {
            return true;
        }
        localScroll.put(target.id(), next);
        rebuild();
        return true;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        com.cloudcore.api.document.UiElement hit =
                com.cloudcore.client.render.ElementHitTester.findWithEvent(document, width,
                        height, (int) event.x(), (int) event.y(),
                        id -> localScroll.getOrDefault(id, 0), "click");
        if (hit != null) {
            Integer actionId = hit.events().get("click");
            if (actionId != null) {
                com.cloudcore.client.CloudCoreClient.sendEvent(document.id(), hit.id(), actionId,
                        java.util.Map.of(
                                "button", new com.cloudcore.protocol.WireValue.IntValue(event.button()),
                                "shift", new com.cloudcore.protocol.WireValue.BoolValue(
                                        event.hasShiftDown())));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        if (!closed) {
            closed = true;
            try {
                onCloseAction.run();
            } finally {
                minecraft.setScreen(null);
            }
            return;
        }
        minecraft.setScreen(null);
    }
}
