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
    private String hoveredId;
    private final java.util.Map<String, Integer> localScroll = new java.util.HashMap<>();
    private final java.util.List<net.minecraft.client.gui.components.EditBox> textFields =
            new java.util.ArrayList<>();
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
        // Text fields are real vanilla EditBox widgets so IME, selection and clipboard stay
        // vanilla; the document still draws their background.
        textFields.clear();
        java.util.List<com.cloudcore.client.screen.LayoutBoxes.Box> fields =
                com.cloudcore.client.screen.LayoutBoxes.ofType(document, width, height,
                        "text_field");
        if (!fields.isEmpty()) {
            LOGGER.debug("Adding {} text field widget(s) for ui={}", fields.size(),
                    document.id());
        }
        for (com.cloudcore.client.screen.LayoutBoxes.Box box : fields) {
            com.cloudcore.api.document.UiElement element = box.element();
            net.minecraft.client.gui.components.EditBox edit =
                    new net.minecraft.client.gui.components.EditBox(font, box.x(), box.y(),
                            Math.max(20, box.width()), Math.max(12, box.height()),
                            net.minecraft.network.chat.Component.empty());
            edit.setBordered(false);
            edit.setMaxLength(maxLength(element));
            Object hint = element.attributes().get("placeholder");
            if (hint instanceof String text && !text.isEmpty()) {
                edit.setHint(net.minecraft.network.chat.Component.literal(text));
            }
            Object value = element.attributes().get("value");
            if (value instanceof String text) {
                edit.setValue(text);
            }
            Integer changeId = element.events().get("change");
            if (changeId != null) {
                edit.setResponder(text -> com.cloudcore.client.CloudCoreClient.sendEvent(document.id(),
                        element.id(), changeId,
                        java.util.Map.of("value",
                                new com.cloudcore.protocol.WireValue.StringValue(text))));
            }
            addRenderableWidget(edit);
            textFields.add(edit);
        }
    }

    private static int maxLength(com.cloudcore.api.document.UiElement element) {
        Object value = element.attributes().get("max_length");
        return value instanceof Number number ? Math.max(1, Math.min(256, number.intValue()))
                : 32;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.isConfirmation() && getFocused() instanceof net.minecraft.client.gui.components.EditBox edit) {
            for (com.cloudcore.client.screen.LayoutBoxes.Box box
                    : com.cloudcore.client.screen.LayoutBoxes.ofType(document, width, height,
                            "text_field")) {
                Integer submitId = box.element().events().get("submit");
                if (submitId != null && box.x() == edit.getX() && box.y() == edit.getY()) {
                    com.cloudcore.client.CloudCoreClient.sendEvent(document.id(), box.element().id(), submitId,
                            java.util.Map.of("value",
                                    new com.cloudcore.protocol.WireValue.StringValue(
                                            edit.getValue())));
                    return true;
                }
            }
        }
        return super.keyPressed(event);
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
        String hit = hoveredElementId(mouseX, mouseY);
        if (width != cachedWidth || height != cachedHeight
                || cachedTextureGeneration != com.cloudcore.client.render.RemoteTextureCache
                        .generation()
                || !java.util.Objects.equals(hit, hoveredId)) {
            hoveredId = hit;
            rebuild();
        }
        backend.render(graphics, cachedList, new FrameContext(graphics, width, height,
                minecraft.getWindow().getGuiScale(), partialTick));
        // Widgets (EditBox) draw themselves; the render list only paints their background.
        for (net.minecraft.client.gui.components.EditBox field : textFields) {
            field.render(graphics, mouseX, mouseY, partialTick);
        }
        graphics.drawString(font, title, 8, 8, TITLE_COLOR, true);
    }

    private String hoveredElementId(int mouseX, int mouseY) {
        com.cloudcore.api.document.UiElement element =
                com.cloudcore.client.render.ElementHitTester.findWithEvent(document, width,
                        height, mouseX, mouseY, id -> localScroll.getOrDefault(id, 0), "click");
        return element == null ? null : element.id();
    }

    private void rebuild() {
        cachedList = DocumentRenderListBuilder.buildAt(document, width, height,
                (width - Math.round(document.width() * document.scale())) / 2,
                (height - Math.round(document.height() * document.scale())) / 2,
                id -> localScroll.getOrDefault(id, 0), hoveredId);
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
        // Sliders report the clicked fraction; the server decides what it means.
        for (com.cloudcore.client.screen.LayoutBoxes.Box box
                : com.cloudcore.client.screen.LayoutBoxes.ofType(document, width, height,
                        "slider")) {
            if (event.x() >= box.x() && event.x() < box.x() + box.width()
                    && event.y() >= box.y() - 2 && event.y() < box.y() + box.height() + 2) {
                Integer actionId = box.element().events().get("change");
                if (actionId != null) {
                    double amount = Math.max(0.0, Math.min(1.0,
                            (event.x() - box.x()) / Math.max(1, box.width())));
                    com.cloudcore.client.CloudCoreClient.sendEvent(document.id(),
                            box.element().id(), actionId, java.util.Map.of("amount",
                                    new com.cloudcore.protocol.WireValue.DoubleValue(amount)));
                    return true;
                }
            }
        }
        // Checkboxes toggle locally and report the new value; the server owns the state.
        com.cloudcore.api.document.UiElement checkbox =
                com.cloudcore.client.render.ElementHitTester.findWithEvent(document, width,
                        height, (int) event.x(), (int) event.y(),
                        id -> localScroll.getOrDefault(id, 0), "change");
        if (checkbox != null && "checkbox".equals(checkbox.type())) {
            Integer actionId = checkbox.events().get("change");
            boolean checked = Boolean.TRUE.equals(checkbox.attributes().get("checked"));
            if (actionId != null) {
                com.cloudcore.client.CloudCoreClient.sendEvent(document.id(), checkbox.id(),
                        actionId, java.util.Map.of("checked",
                                new com.cloudcore.protocol.WireValue.BoolValue(!checked)));
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
