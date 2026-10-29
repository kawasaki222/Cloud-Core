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
import com.cloudcore.client.render.TextFieldChrome;
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

    private UiDocument document;
    private final DocumentRenderer backend = new DocumentRenderer();
    private final Runnable onCloseAction;
    private RenderList cachedList = RenderList.EMPTY;
    private int cachedWidth = -1;
    private int cachedHeight = -1;
    private int cachedTextureGeneration = -1;
    private String hoveredId;
    /** Element held down by the left mouse button; cleared on release. */
    private String pressedId;
    /** Pressed element the cached list was built for, so the press repaints exactly once. */
    private String cachedPressedId;
    private final Map<String, Integer> localScroll = new HashMap<>();
    private final List<EditBox> textFields = new ArrayList<>();
    /**
     * Element box for each field, keyed by widget. Fields are inset by their padding, so the
     * widget's own x/y no longer equals the element's; the focus ring needs the element box.
     */
    private final Map<EditBox, LayoutBoxes.Box> textFieldBoxes = new java.util.LinkedHashMap<>();
    private UiElement hoverEventElement;
    private String focusId;
    private boolean closed;
    /** Non-null while a slider is being dragged; cleared on release. */
    private SliderDrag sliderDrag;

    /** Drag bookkeeping for one slider: which element, which action, and its track geometry. */
    private record SliderDrag(String elementId, int actionId, int trackX, int trackWidth) {
    }

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

    /**
     * Applies an incremental update from the server. Text field widgets keep their live state
     * (typed text, selection, focus); the server only sends property updates for elements it
     * knows the widget can absorb, and falls back to a full definition for text fields.
     */
    public void updateDocument(UiDocument updated) {
        this.document = Objects.requireNonNull(updated, "updated");
        // A server revision used to replace the whole screen, which reset the wheel delta.
        // Keep that behaviour: the document's own scroll_y is authoritative again.
        localScroll.clear();
        hoveredId = null;
        pressedId = null;
        cachedPressedId = null;
        hoverEventElement = null;
        rebuild();
    }

    @Override
    protected void init() {
        // Text fields are real vanilla EditBox widgets so IME, selection and clipboard stay
        // vanilla; the document still draws their background.
        textFields.clear();
        textFieldBoxes.clear();
        List<LayoutBoxes.Box> fields = LayoutBoxes.ofType(document, width, height, "text_field");
        if (!fields.isEmpty()) {
            LOGGER.debug("Adding {} text field widget(s) for ui={}", fields.size(), document.id());
        }
        for (LayoutBoxes.Box box : fields) {
            UiElement element = box.element();
            // EditBox draws its text flush against its own left edge, so the padding is
            // subtracted from the widget instead of drawn by us: the content lands where the
            // page author asked for it. The clear button owns a strip on the right, so the
            // widget stops before it and typed text cannot slide underneath.
            int[] inset = padding(element);
            int clearStrip = TextFieldChrome.hasClearButton(element)
                    ? TextFieldChrome.clearBar(box.x(), box.y(), box.width(), box.height()).size()
                            + 6
                    : 0;
            int fieldX = box.x() + inset[0];
            int fieldY = box.y() + inset[1];
            int fieldW = Math.max(20, box.width() - inset[0] - inset[2] - clearStrip);
            int fieldH = Math.max(12, box.height() - inset[1] - inset[3]);
            CloudEditBox edit = new CloudEditBox(font, fieldX, fieldY, fieldW, fieldH,
                    Component.empty());
            edit.setCursorColor(TextFieldChrome.cursorColor(element));
            edit.setSelectionColor(TextFieldChrome.selectionColor(element));
            edit.setHintColor(TextFieldChrome.placeholderColor(element));
            edit.setBordered(false);
            edit.setEditable(element.enabled());
            edit.setMaxLength(maxLength(element));
            edit.setFilter(filterPredicate(stringAttribute(element, "filter")));
            Object hint = element.attributes().get("placeholder");
            if (hint instanceof String text && !text.isEmpty()) {
                edit.setHint(Component.literal(text));
            }
            Object value = element.attributes().get("value");
            if (value instanceof String text) {
                edit.setValue(text);
            }
            edit.setTextColor(com.cloudcore.client.render.Colors.parse(styleText(element, "color"),
                    0xFFE0E0E0));
            if (truthy(element.attributes().get("sensitive"))) {
                // Display-only mask: the server still receives and validates the plain text.
                edit.addFormatter((text, cursor) -> net.minecraft.util.FormattedCharSequence
                        .forward("*".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
            }
            Integer changeId = element.events().get("change");
            if (changeId != null) {
                edit.setResponder(text -> com.cloudcore.client.CloudCoreClient.sendEvent(
                        document.id(), element.id(), changeId,
                        Map.of("value", new WireValue.StringValue(text))));
            }
            addRenderableWidget(edit);
            textFields.add(edit);
            textFieldBoxes.put(edit, box);
        }
    }

    /** {@code style.padding} insets for this field, following the CSS shorthand in {@link Insets}. */
    private static int[] padding(UiElement element) {
        Object style = element.attributes().get("style");
        Object value = style instanceof Map<?, ?> map ? map.get("padding") : null;
        return com.cloudcore.client.render.Insets.parse(value);
    }

    private static int maxLength(UiElement element) {
        Object value = element.attributes().get("max_length");
        return value instanceof Number number ? Math.max(1, Math.min(256, number.intValue())) : 32;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return !text.isEmpty() && !"false".equalsIgnoreCase(text);
        }
        return value != null;
    }

    private static String stringAttribute(UiElement element, String key) {
        Object value = element.attributes().get(key);
        return value instanceof String text ? text.trim() : null;
    }

    /** Reads {@code style.<group>.<key>} (e.g. {@code style.text.color}). */
    private static Object styleText(UiElement element, String key) {
        Object style = element.attributes().get("style");
        if (style instanceof Map<?, ?> map && map.get("text") instanceof Map<?, ?> text) {
            return text.get(key);
        }
        return null;
    }

    /** Reads {@code style.border.<state>}. */
    private static Object styleBorder(UiElement element, String state) {
        Object style = element.attributes().get("style");
        if (style instanceof Map<?, ?> map && map.get("border") instanceof Map<?, ?> border) {
            return border.get(state);
        }
        return null;
    }

    /**
     * Input filter for a text field: {@code printable} (default when absent),
     * {@code digits}/{@code numeric}, {@code alnum}, {@code none}, or {@code regex:<pattern>}.
     * Unknown values allow input and log {@code CC-INPUT-002} rather than locking a field.
     */
    private static java.util.function.Predicate<String> filterPredicate(String filter) {
        if (filter == null || filter.isBlank()) {
            return value -> true;
        }
        String normalised = filter.toLowerCase(java.util.Locale.ROOT);
        if (normalised.startsWith("regex:")) {
            String source = filter.substring(6).trim();
            try {
                java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(source);
                return value -> compiled.matcher(value).matches();
            } catch (java.util.regex.PatternSyntaxException exception) {
                LOGGER.warn("[CC-INPUT-001] Invalid text_field filter '{}': {}", source,
                        exception.getDescription());
                return value -> true;
            }
        }
        return switch (normalised) {
            case "digits", "numeric" -> value -> value.chars().allMatch(Character::isDigit);
            case "alnum" -> value -> value.chars().allMatch(Character::isLetterOrDigit);
            case "printable" -> value -> value.chars().noneMatch(ch -> ch < 0x20 || ch == 0x7F);
            case "none", "any" -> value -> true;
            default -> {
                LOGGER.warn("[CC-INPUT-002] Unknown text_field filter '{}'; allowing input",
                        filter);
                yield value -> true;
            }
        };
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.isConfirmation() && getFocused() instanceof EditBox edit) {
            // Match by widget identity: padding insets the widget, so comparing coordinates
            // misses every padded field.
            LayoutBoxes.Box box = textFieldBoxes.get(edit);
            Integer submitId = box == null ? null : box.element().events().get("submit");
            if (submitId != null) {
                com.cloudcore.client.CloudCoreClient.sendEvent(document.id(),
                        box.element().id(), submitId,
                        Map.of("value", new WireValue.StringValue(edit.getValue())));
                return true;
            }
        }
        // Keyboard focus for buttons: Tab (or the arrows) cycles, Enter/Space activates.
        // A focused text field keeps Tab for the vanilla widget chain.
        if (!(getFocused() instanceof EditBox)) {
            if (event.isCycleFocus()) {
                cycleFocus(event.hasShiftDown());
                return true;
            }
            if (event.isUp() || event.isDown() || event.isLeft() || event.isRight()) {
                // Only claim the arrows when there is something to move focus to.
                if (!focusables().isEmpty()) {
                    cycleFocus(event.isUp() || event.isLeft());
                    return true;
                }
            }
            if (event.isConfirmation() || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
                LayoutBoxes.Box box = focusedBox();
                if (box != null) {
                    activate(box.element(), 0, event.hasShiftDown(), "click");
                    return true;
                }
            }
        }
        return super.keyPressed(event);
    }

    /** Clickable controls in document order; enabled elements that declare a click event. */
    private List<LayoutBoxes.Box> focusables() {
        List<LayoutBoxes.Box> boxes = LayoutBoxes.ofTypes(document, width, height,
                java.util.Set.of("button", "icon_button"));
        boxes.removeIf(box -> !box.element().enabled()
                || !box.element().events().containsKey("click"));
        return boxes;
    }

    private LayoutBoxes.Box focusedBox() {
        if (focusId == null) {
            return null;
        }
        for (LayoutBoxes.Box box : focusables()) {
            if (box.element().id().equals(focusId)) {
                return box;
            }
        }
        return null;
    }

    private void cycleFocus(boolean backwards) {
        List<LayoutBoxes.Box> boxes = focusables();
        if (boxes.isEmpty()) {
            focusId = null;
            return;
        }
        int current = -1;
        for (int index = 0; index < boxes.size(); index++) {
            if (boxes.get(index).element().id().equals(focusId)) {
                current = index;
                break;
            }
        }
        int next = current < 0
                ? (backwards ? boxes.size() - 1 : 0)
                : Math.floorMod(current + (backwards ? -1 : 1), boxes.size());
        focusId = boxes.get(next).element().id();
        LOGGER.debug("Keyboard focus ui={} element={}", document.id(), focusId);
    }

    private void activate(UiElement element, int button, boolean shift, String event) {
        Integer actionId = element.events().get(event);
        if (actionId == null) {
            return;
        }
        com.cloudcore.client.CloudCoreClient.sendEvent(document.id(), element.id(), actionId,
                Map.of("button", new WireValue.IntValue(button),
                        "shift", new WireValue.BoolValue(shift)));
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
        // A press only shows while the cursor is still on the element, so losing the hover
        // (or releasing) has to refresh the list too, which the same comparison covers.
        String held = pressedId;
        if (held != null && !held.equals(hit)) {
            pressedId = null;
            held = null;
        }
        if (width != cachedWidth || height != cachedHeight
                || cachedTextureGeneration != com.cloudcore.client.render.RemoteTextureCache
                        .generation()
                || !Objects.equals(hit, hoveredId) || !Objects.equals(held, cachedPressedId)) {
            hoveredId = hit;
            cachedPressedId = held;
            rebuild();
        }
        updateHoverEvents(mouseX, mouseY);
        backend.render(graphics, cachedList, new FrameContext(graphics, width, height,
                minecraft.getWindow().getGuiScale(), partialTick));
        // Widgets (EditBox) draw themselves; the render list only paints their background.
        for (EditBox field : textFields) {
            field.render(graphics, mouseX, mouseY, partialTick);
        }
        drawFieldFocus(graphics);
        drawKeyboardFocus(graphics);
    }

    /**
     * A 1px ring around the keyboard-focused control, using {@code style.border.focused} when
     * the page declares one. Drawn per frame: focus is client state, not document state.
     */
    private void drawKeyboardFocus(GuiGraphics graphics) {
        LayoutBoxes.Box box = focusedBox();
        if (box == null) {
            return;
        }
        int argb = com.cloudcore.client.render.Colors.parse(styleBorder(box.element(),
                "focused"), 0xFF4FA3E8);
        // Two rings: a dark outer one keeps the focus visible on light backgrounds too.
        drawRing(graphics, box.x() - 2, box.y() - 2, box.width() + 4, box.height() + 4,
                0xFF101010);
        drawRing(graphics, box.x() - 1, box.y() - 1, box.width() + 2, box.height() + 2, argb);
    }

    private static void drawRing(GuiGraphics graphics, int x, int y, int width, int height,
            int argb) {
        graphics.fill(x, y, x + width, y + 1, argb);
        graphics.fill(x, y + height - 1, x + width, y + height, argb);
        graphics.fill(x, y + 1, x + 1, y + height - 1, argb);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, argb);
    }

    /**
     * Focus lives on the widget, not in the document, so the focused border is drawn per frame
     * instead of inside the cached render list.
     */
    private void drawFieldFocus(GuiGraphics graphics) {
        if (!(getFocused() instanceof EditBox focused)) {
            return;
        }
        // The widget is inset by its padding, so match by widget identity instead of by
        // position: comparing coordinates would miss every inset field.
        LayoutBoxes.Box box = textFieldBoxes.get(focused);
        if (box == null) {
            return;
        }
        int argb = com.cloudcore.client.render.Colors.parse(styleBorder(box.element(),
                "focused"), 0xFF4FA3E8);
        int x = box.x();
        int y = box.y();
        int w = box.width();
        int h = box.height();
        graphics.fill(x, y, x + w, y + 1, argb);
        graphics.fill(x, y + h - 1, x + w, y + h, argb);
        graphics.fill(x, y + 1, x + 1, y + h - 1, argb);
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
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
                this::scrolled, hoveredId, pressedId);
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

    /**
     * Reports a slider position as the {@code amount} fraction plus the resolved {@code value}.
     * {@code amount} stays {@code 0..1} so existing actions keep working; {@code value} carries
     * the document's {@code min}/{@code max}/{@code step} mapping for pages that need the real
     * number.
     */
    private void reportSlider(SliderDrag drag, double mouseX, boolean dragging) {
        // A recursive lookup: a slider inside a row/column/grid is not a top-level element,
        // and scanning only the top level silently dropped every nested slider's reports.
        UiElement element = UiElements.findById(document, drag.elementId());
        if (element == null) {
            return;
        }
        double amount = Math.max(0.0, Math.min(1.0,
                (mouseX - drag.trackX()) / Math.max(1, drag.trackWidth())));
        double min = number(element, "min", 0.0);
        double max = com.cloudcore.client.render.Sliders.maxOrDefault(min,
                number(element, "max", 1.0));
        double step = com.cloudcore.client.render.Sliders.stepOrDefault(
                number(element, "step", 0.0));
        double value = com.cloudcore.client.render.Sliders.value(amount, min, max, step);
        // Without live reporting the drag stays local and only the release reaches the server.
        if (dragging && !truthy(element.attributes().get("live"))) {
            return;
        }
        com.cloudcore.client.CloudCoreClient.sendEvent(document.id(), drag.elementId(),
                drag.actionId(), Map.of(
                        "amount", new WireValue.DoubleValue(amount),
                        "value", new WireValue.DoubleValue(value)));
    }

    private static double number(UiElement element, String key, double fallback) {
        Object value = element.attributes().get(key);
        if (value instanceof Number numeric) {
            return numeric.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event,
            double dragX, double dragY) {
        if (sliderDrag != null) {
            reportSlider(sliderDrag, event.x(), true);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (event.button() == 0 && pressedId != null) {
            // Releasing always ends the press, even if the cursor left the button: it must not
            // stay drawn as held down.
            pressedId = null;
            rebuild();
        }
        if (sliderDrag != null && event.button() == 0) {
            // Final position always goes out, so a non-live slider still commits on release.
            reportSlider(sliderDrag, event.x(), false);
            sliderDrag = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && clearTextFieldAt((int) event.x(), (int) event.y())) {
            return true;
        }
        // Right click prefers an element that declares right_click and falls back to click,
        // so a button can offer a secondary action without losing its primary one.
        boolean rightClick = event.button() == 1;
        String eventName = rightClick ? "right_click" : "click";
        UiElement hit = ElementHitTester.findWithEvent(document, width, height, (int) event.x(),
                (int) event.y(), this::scrolled, eventName);
        if (hit == null && rightClick) {
            eventName = "click";
            hit = ElementHitTester.findWithEvent(document, width, height, (int) event.x(),
                    (int) event.y(), this::scrolled, eventName);
        }
        if (hit != null) {
            // Left-press paints the button as held until release; the click still fires now,
            // so a press and a click never disagree about what was activated.
            if (!rightClick && hit.enabled()) {
                pressedId = hit.id();
            }
            activate(hit, event.button(), event.hasShiftDown(), eventName);
            return true;
        }
        // Sliders report the clicked fraction; the server decides what it means. The press also
        // starts a drag so the knob follows the cursor while the button stays down.
        for (LayoutBoxes.Box box : LayoutBoxes.ofType(document, width, height, "slider")) {
            if (event.x() >= box.x() && event.x() < box.x() + box.width()
                    && event.y() >= box.y() - 2 && event.y() < box.y() + box.height() + 2) {
                Integer actionId = box.element().events().get("change");
                if (actionId != null) {
                    sliderDrag = new SliderDrag(box.element().id(), actionId, box.x(),
                            box.width());
                    reportSlider(sliderDrag, event.x(), false);
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

    /**
     * The clear button belongs to the document, not to the vanilla widget, so it is hit-tested
     * here first: {@code EditBox} would otherwise claim the click as a caret placement.
     * Clearing goes through {@link EditBox#setValue}, which fires the {@code change} responder.
     */
    private boolean clearTextFieldAt(int mouseX, int mouseY) {
        for (Map.Entry<EditBox, LayoutBoxes.Box> entry : textFieldBoxes.entrySet()) {
            UiElement element = entry.getValue().element();
            if (!element.enabled() || !TextFieldChrome.hasClearButton(element)) {
                continue;
            }
            LayoutBoxes.Box box = entry.getValue();
            TextFieldChrome.ClearBar bar = TextFieldChrome.clearBar(box.x(), box.y(),
                    box.width(), box.height());
            if (mouseX >= bar.x() && mouseX < bar.x() + bar.size()
                    && mouseY >= bar.y() && mouseY < bar.y() + bar.size()) {
                EditBox edit = entry.getKey();
                edit.setValue("");
                edit.setFocused(true);
                return true;
            }
        }
        return false;
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
