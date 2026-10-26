package com.cloudcore.client.screen;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.client.render.DocumentRenderListBuilder;
import com.cloudcore.client.render.FrameContext;
import com.cloudcore.client.render.Layer;
import com.cloudcore.client.render.NativeBackend;
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
    private final NativeBackend backend = new NativeBackend();
    private final Runnable onCloseAction;
    private RenderList cachedList = RenderList.EMPTY;
    private int cachedWidth = -1;
    private int cachedHeight = -1;
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
        if (width != cachedWidth || height != cachedHeight) {
            cachedList = DocumentRenderListBuilder.build(document, width, height);
            cachedWidth = width;
            cachedHeight = height;
            LOGGER.debug("Built render list for ui={} commands={}", document.id(),
                    cachedList.commands().size());
        }
        backend.beginFrame(new FrameContext(graphics, width, height,
                minecraft.getWindow().getGuiScale(), partialTick));
        try {
            backend.draw(cachedList, Layer.BACKGROUND);
        } finally {
            backend.endFrame();
        }
        graphics.drawString(font, title, 8, 8, TITLE_COLOR, true);
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
