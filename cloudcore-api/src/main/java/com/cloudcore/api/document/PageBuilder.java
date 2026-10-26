package com.cloudcore.api.document;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.page.PageLoader.CompiledPage;
import com.cloudcore.api.page.PageLoader.PageAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a page in Java instead of YAML, for pages whose content depends on runtime data
 * (a player's quests, a shop filtered by level). The result is an ordinary
 * {@link CompiledPage}: the same document, the same action table, the same validator as a
 * YAML page, so the server sends it through the same path.
 *
 * <pre>{@code
 * CompiledPage page = PageBuilder.create("rpg_shop", SurfaceType.CUSTOM_SCREEN, 240, 120)
 *         .theme("dark")
 *         .panel("root", 0, 0, 240, 120, "#CC202830")
 *         .label("title", 12, 10, "RPG 商店")
 *         .button("buy", 12, 40, 100, 20, "购买", "rpg_buy")
 *         .build();
 * }</pre>
 *
 * <p>Only plain attributes are supported: {@code url:} textures are a YAML-compiler feature,
 * so a code-built page uses {@code local:}/{@code pack:}/{@code builtin:}/{@code inline:}
 * references (or colours). {@code size} is fixed at creation.
 *
 * <p>Not thread-safe; build on the main thread and hand the result to the open call.
 */
public final class PageBuilder {
    private final String id;
    private final SurfaceType surface;
    private final int width;
    private final int height;
    private String theme;
    private final List<Draft> elements = new ArrayList<>();
    private final Map<String, Draft> byId = new LinkedHashMap<>();
    private final Map<Integer, List<PageAction>> actions = new LinkedHashMap<>();
    private final Map<String, Object> state = new LinkedHashMap<>();
    private int nextActionId = 1;

    private PageBuilder(String id, SurfaceType surface, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Page size must be positive: " + width + "x" + height);
        }
        this.id = Objects.requireNonNull(id, "id");
        this.surface = Objects.requireNonNull(surface, "surface");
        this.width = width;
        this.height = height;
    }

    public static PageBuilder create(String id, SurfaceType surface, int width, int height) {
        return new PageBuilder(id, surface, width, height);
    }

    public PageBuilder theme(String theme) {
        this.theme = theme;
        return this;
    }

    /** Seed state used by {@code ${state.key}} when the page's actions run. */
    public PageBuilder state(String key, Object value) {
        state.put(Objects.requireNonNull(key, "key"), value);
        return this;
    }

    public PageBuilder panel(String id, int x, int y, int width, int height, String background) {
        Draft draft = draft(id, "panel", x, y, width, height);
        if (background != null) {
            draft.attributes.put("style", Map.of("background", background));
        }
        return this;
    }

    public PageBuilder label(String id, int x, int y, String text) {
        return label(id, x, y, text, null);
    }

    public PageBuilder label(String id, int x, int y, String text, String color) {
        Draft draft = draft(id, "label", x, y, 0, 0);
        draft.attributes.put("text", Objects.requireNonNull(text, "text"));
        if (color != null) {
            draft.attributes.put("color", color);
        }
        return this;
    }

    /** A button whose label is also the action name bound to {@code click}. */
    public PageBuilder button(String id, int x, int y, int width, int height, String text,
            String action) {
        return button(id, x, y, width, height, text, action, Map.of());
    }

    /** A button bound to {@code click} with action parameters. */
    public PageBuilder button(String id, int x, int y, int width, int height, String text,
            String action, Map<String, Object> params) {
        Draft draft = draft(id, "button", x, y, width, height);
        draft.attributes.put("text", Objects.requireNonNull(text, "text"));
        bind(draft, "click", action, params);
        return this;
    }

    public PageBuilder itemIcon(String id, int x, int y, String itemId, int count) {
        Draft draft = draft(id, "item_icon", x, y, 16, 16);
        draft.attributes.put("item", Map.of("id", Objects.requireNonNull(itemId, "itemId"),
                "count", Math.max(1, count)));
        return this;
    }

    /** Binds an event to an action without any parameters. */
    public PageBuilder on(String elementId, String event, String action) {
        return on(elementId, event, action, Map.of());
    }

    /**
     * Binds an event to an action; parameters arrive in the handler's {@code args}. Binding the
     * same event twice appends a second action, like a YAML {@code on:} list.
     */
    public PageBuilder on(String elementId, String event, String action,
            Map<String, Object> params) {
        bind(require(elementId), Objects.requireNonNull(event, "event"),
                Objects.requireNonNull(action, "action"), params);
        return this;
    }

    /** Sets any attribute the element supports (colour, nine_slice, width, checked, ...). */
    public PageBuilder attribute(String elementId, String key, Object value) {
        require(elementId).attributes.put(Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(value, "value"));
        return this;
    }

    /**
     * @return the validated page
     * @throws IllegalArgumentException when an element id repeats or the document fails the
     *         same validation the YAML compiler uses
     */
    public CompiledPage build() {
        List<UiElement> built = new ArrayList<>(elements.size());
        for (Draft draft : elements) {
            built.add(new UiElement(draft.id, draft.type, draft.x, draft.y,
                    draft.width == 0 ? null : String.valueOf(draft.width),
                    draft.height == 0 ? null : String.valueOf(draft.height),
                    true, true, 0, Map.copyOf(draft.events), List.of(),
                    new LinkedHashMap<>(draft.attributes)));
        }
        UiDocument document = new UiDocument(UiDocument.CURRENT_SCHEMA, id, surface,
                TextRuns.plain(id), theme, width, height, 1.0f, null, Map.of(), Map.of(), built);
        ValidationResult validation = UiDocumentValidator.validate(document);
        if (!validation.valid()) {
            ValidationIssue issue = validation.errors().get(0);
            throw new IllegalArgumentException("Page '" + id + "' is invalid: " + issue.code()
                    + " " + issue.path() + ": " + issue.message());
        }
        Map<Integer, List<PageAction>> actionSnapshot = new LinkedHashMap<>();
        actions.forEach((actionId, list) -> actionSnapshot.put(actionId, List.copyOf(list)));
        return new CompiledPage(document, actionSnapshot,
                new LinkedHashMap<>(state), List.of());
    }

    private Draft draft(String id, String type, int x, int y, int width, int height) {
        if (byId.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate element id '" + id + "'");
        }
        Draft draft = new Draft(id, type, x, y, width, height);
        byId.put(id, draft);
        elements.add(draft);
        return draft;
    }

    private Draft require(String elementId) {
        Draft draft = byId.get(elementId);
        if (draft == null) {
            throw new IllegalArgumentException("Unknown element id '" + elementId + "'");
        }
        return draft;
    }

    private void bind(Draft draft, String event, String action, Map<String, Object> params) {
        PageAction pageAction = new PageAction(action,
                params == null ? Map.of() : new LinkedHashMap<>(params));
        Integer existing = draft.events.get(event);
        if (existing == null) {
            int actionId = nextActionId++;
            draft.events.put(event, actionId);
            List<PageAction> list = new ArrayList<>();
            list.add(pageAction);
            actions.put(actionId, list);
        } else {
            actions.get(existing).add(pageAction);
        }
    }

    private static final class Draft {
        private final String id;
        private final String type;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private final Map<String, Integer> events = new LinkedHashMap<>();

        private Draft(String id, String type, int x, int y, int width, int height) {
            this.id = Objects.requireNonNull(id, "id");
            this.type = type;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }
}
