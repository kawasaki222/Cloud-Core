package com.cloudcore.client.screen;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import com.cloudcore.api.document.UiElements;
import com.cloudcore.client.render.DocumentRenderListBuilder;
import com.cloudcore.client.render.DocumentRenderer;
import com.cloudcore.client.render.ElementHitTester;
import com.cloudcore.client.render.FrameContext;
import com.cloudcore.client.render.RenderList;
import com.cloudcore.client.render.ScrollTargets;
import com.cloudcore.protocol.WireValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A custom screen that renders one document and reports input as events.
 *
 * <p>The client owns no state: clicks, wheel scrolling, hover crossings and text input are
 * reported to the server, which may answer with a newer revision of the same document.
 */
public final class CloudScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final int BACKGROUND = 0xB0000000;
    private static final int SCROLL_STEP = 12;

    private final UiDocument document;
    private final DocumentRenderer backend = new DocumentRenderer();
    private final Runnable onCloseAction;
    private RenderList cachedList = RenderList.EMPTY;
    private int cachedWidth = -1;
    private int cachedHeight = -1;
    private int cachedTextureGeneration = -1;
    private String hoveredId;
    private final Map<String, Integer> localScroll = new HashMap<>();
    private final List<EditBox> textFields = new ArrayList<>();
    private UiElement hoverEventElement;
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
        List<LayoutBoxes.Box> fields = LayoutBoxes.ofType(document, width, height, "text_field");
        if (!fields.isEmpty()) {
            LOGGER.debug("Adding {} text field widget(s) for ui={}", fields.size(), document.id());
        }
        for (LayoutBoxes.Box box : fields) {
            UiElement element = box.element();
            EditBox edit = new EditBox(font, box.x(), box.y(), Math.max(20, box.width()),
                    Math.max(12, box.height()), Component.empty());
            edit.setBordered(false);
            edit.setMaxLength(maxLength(element));
            Object hint = element.attributes().get("placeholder");
            if (hint instanceof String text && !text.isEmpty()) {
                edit.setHint(Component.literal(text));
            }
            Object value = element.attributes().get("value");
            if (value instanceof String text) {
                edit.setValue(text);
            }
            Integer changeId = element.events().get("change");
            if (changeId != null) {
                edit.setResponder(text -> com.cloudcore.client.CloudCoreClient.sendEvent(
                        document.id(), element.id(), changeId,
                        Map.of("value", new WireValue.StringValue(text))));
            }
            addRenderableWidget(edit);
            textFields.add(edit);
        }
    }

    private static int maxLength(UiElement element) {
        Object value = element.attributes().get("max_length");
        return value instanceof Number number ? Math.max(1, Math.min(256, number.intValue())) : 32;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.isConfirmation() && getFocused() instanceof EditBox edit) {
            for (LayoutBoxes.Box box : LayoutBoxes.ofType(document, width, height,
                    "text_field")) {
                Integer submitId = box.element().events().get("submit");
                if (submitId != null && box.x() == edit.getX() && box.y() == edit.getY()) {
                    com.cloudcore.client.CloudCoreClient.sendEvent(document.id(),
                            box.element().id(), submitId,
                            Map.of("value", new WireValue.StringValue(edit.getValue())));
                    return true;
                }
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        if (Boolean.getBoolean("cloudcore.nanovg.spike")) {
            boolean drawn = com.cloudcore.client.render.NanoVgSpike.composite(graphics, 8,
                    height - 72, 64);
            int controlX = drawn ? 80 : 8;
            graphics.fill(controlX, height - 72, controlX + 64, height - 8, 0xFFFF0000);
        }
        String hit = hoveredElementId(mouseX, mouseY);
        if (width != cachedWidth || height != cachedHeight
                || cachedTextureGeneration != com.cloudcore.client.render.RemoteTextureCache
                        .generation()
                || !Objects.equals(hit, hoveredId)) {
            hoveredId = hit;
            rebuild();
        }
        updateHoverEvents(mouseX, mouseY);
        backend.render(graphics, cachedList, new FrameContext(graphics, width, height,
                minecraft.getWindow().getGuiScale(), partialTick));
        // Widgets (EditBox) draw themselves; the render list only paints their background.
        for (EditBox field : textFields) {
            field.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private String hoveredElementId(int mouseX, int mouseY) {
        UiElement element = ElementHitTester.findWithEvent(document, width, height, mouseX,
                mouseY, this::scrolled, "click");
        return element == null ? null : element.id();
    }

    /**
     * Reports a hover crossing once, and only for elements that declare the event, so pages
     * without hover handlers pay nothing. The previous element gets {@code hover_leave} first.
     */
    private void updateHoverEvents(int mouseX, int mouseY) {
        UiElement target = ElementHitTester.findWithEvent(document, width, height, mouseX,
                mouseY, this::scrolled, "hover_enter");
        if (target == null) {
            target = ElementHitTester.findWithEvent(document, width, height, mouseX, mouseY,
                    this::scrolled, "hover_leave");
        }
        if (target == hoverEventElement) {
            return;
        }
        UiElement previous = hoverEventElement;
        hoverEventElement = target;
        if (previous != null) {
            Integer actionId = previous.events().get("hover_leave");
            if (actionId != null) {
                com.cloudcore.client.CloudCoreClient.sendEvent(document.id(), previous.id(),
                        actionId, Map.of());
            }
        }
        if (target != null) {
            Integer actionId = target.events().get("hover_enter");
            if (actionId != null) {
                com.cloudcore.client.CloudCoreClient.sendEvent(document.id(), target.id(),
                        actionId, Map.of());
            }
        }
    }

    private void rebuild() {
        cachedList = DocumentRenderListBuilder.buildAt(document, width, height,
                (width - Math.round(document.width() * document.scale())) / 2,
                (height - Math.round(document.height() * document.scale())) / 2,
                this::scrolled, hoveredId);
        cachedWidth = width;
        cachedHeight = height;
        cachedTextureGeneration = com.cloudcore.client.render.RemoteTextureCache.generation();
        LOGGER.debug("Built render list for ui={} commands={}", document.id(),
                cachedList.commands().size());
    }

    /** Scroll offset authored by the document; a server-side state usually drives it. */
    private int documentScroll(String elementId) {
        UiElement element = UiElements.findById(document, elementId);
        Object value = element == null ? null : element.attributes().get("scroll_y");
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(0, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Total offset applied to a scroll element: the document's own {@code scroll_y} plus this
     * screen's wheel delta. A newer revision resets the delta, which lets the server keep the
     * authoritative offset in state.
     */
    private int scrolled(String elementId) {
        return documentScroll(elementId) + localScroll.getOrDefault(elementId, 0);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        ScrollTargets.Target target = ScrollTargets.find(document, width, height, (int) mouseX,
                (int) mouseY, this::scrolled);
        if (target == null || scrollY == 0.0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        // Wheel up (positive) scrolls the content back towards its start.
        int next = Math.max(0, Math.min(target.maxScroll(),
                target.current() - (int) Math.round(scrollY * SCROLL_STEP)));
        if (next == target.current()) {
            return true;
        }
        localScroll.put(target.id(), next - documentScroll(target.id()));
        rebuild();
        reportScroll(target.id(), next);
        return true;
    }

    private void reportScroll(String elementId, int offset) {
        UiElement element = UiElements.findById(document, elementId);
        Integer actionId = element == null ? null : element.events().get("scroll");
        if (actionId != null) {
            com.cloudcore.client.CloudCoreClient.sendEvent(document.id(), elementId, actionId,
                    Map.of("scroll", new WireValue.IntValue(offset)));
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        UiElement hit = ElementHitTester.findWithEvent(document, width, height, (int) event.x(),
                (int) event.y(), this::scrolled, "click");
        if (hit != null) {
            Integer actionId = hit.events().get("click");
            if (actionId != null) {
                com.cloudcore.client.CloudCoreClient.sendEvent(document.id(), hit.id(), actionId,
                        Map.of("button", new WireValue.IntValue(event.button()),
                                "shift", new WireValue.BoolValue(event.hasShiftDown())));
                return true;
            }
        }
        // Sliders report the clicked fraction; the server decides what it means.
        for (LayoutBoxes.Box box : LayoutBoxes.ofType(document, width, height, "slider")) {
            if (event.x() >= box.x() && event.x() < box.x() + box.width()
                    && event.y() >= box.y() - 2 && event.y() < box.y() + box.height() + 2) {
                Integer actionId = box.element().events().get("change");
                if (actionId != null) {
                    double amount = Math.max(0.0, Math.min(1.0,
                            (event.x() - box.x()) / Math.max(1, box.width())));
                    com.cloudcore.client.CloudCoreClient.sendEvent(document.id(),
                            box.element().id(), actionId,
                            Map.of("amount", new WireValue.DoubleValue(amount)));
                    return true;
                }
            }
        }
        // Checkboxes toggle locally and report the new value; the server owns the state.
        UiElement checkbox = ElementHitTester.findWithEvent(document, width, height,
                (int) event.x(), (int) event.y(), this::scrolled, "change");
        if (checkbox != null && "checkbox".equals(checkbox.type())) {
            Integer actionId = checkbox.events().get("change");
            boolean checked = Boolean.TRUE.equals(checkbox.attributes().get("checked"));
            if (actionId != null) {
                com.cloudcore.client.CloudCoreClient.sendEvent(document.id(), checkbox.id(),
                        actionId, Map.of("checked", new WireValue.BoolValue(!checked)));
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
