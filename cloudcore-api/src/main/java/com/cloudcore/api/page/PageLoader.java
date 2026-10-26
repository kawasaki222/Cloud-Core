package com.cloudcore.api.page;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.ContainerSpec;
import com.cloudcore.api.document.FontSpec;
import com.cloudcore.api.document.TextRun;
import com.cloudcore.api.document.TextRuns;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import com.cloudcore.api.document.UiDocumentValidator;
import com.cloudcore.api.document.ValidationIssue;
import com.cloudcore.api.document.ValidationResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads an authored YAML page (single file or a directory with {@code page.yml}) into the
 * compiled {@link UiDocument} the client receives.
 *
 * <p>Composition is static (see docs/12-page-composition.md): includes are merged depth-first,
 * elements appended in declaration order, {@code state}/{@code fonts}/{@code surface_options}
 * merged with later values winning. Repeat expansion runs at load time against the supplied
 * state snapshot, so the client never evaluates templates.
 *
 * <p>Pure Java: no Minecraft or Bukkit types.
 */
public final class PageLoader {

    /** One server-side action compiled from {@code on: {event: [ ... ]}}. */
    public record PageAction(String type, Map<String, Object> params) {
        public PageAction {
            Objects.requireNonNull(type, "type");
            params = Map.copyOf(Objects.requireNonNull(params, "params"));
        }
    }

    /**
     * The document plus what stays on the server: action bodies and the state snapshot the
     * page was expanded from (used to resolve {@code ${state.x}} when an action runs).
     */
    public record CompiledPage(UiDocument document,
            Map<Integer, List<PageAction>> actions,
            Map<String, Object> state,
            List<RemoteTexture> textures) {
        public CompiledPage {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(state, "state");
            actions = Map.copyOf(actions);
            state = Map.copyOf(state);
            textures = List.copyOf(textures);
        }
    }

    /** A {@code url:} reference rewritten to {@code inline:<key>} for the client. */
    public record RemoteTexture(String key, String url) {
        public RemoteTexture {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(url, "url");
        }
    }

    private static final class BuildContext {
        private final String pageId;
        private final Map<Integer, List<PageAction>> actions = new LinkedHashMap<>();
        private final Set<Integer> usedActionIds = new LinkedHashSet<>();
        private final Map<String, RemoteTexture> textures = new LinkedHashMap<>();

        private BuildContext(String pageId) {
            this.pageId = pageId;
        }

        private int actionId(String elementId, String event) {
            int candidate = Math.floorMod((pageId + "|" + elementId + "|" + event).hashCode(),
                    65534) + 1;
            while (!usedActionIds.add(candidate)) {
                candidate = candidate % 65534 + 1;
            }
            return candidate;
        }

        /** Rewrites {@code url:https://...} to {@code inline:<key>} and remembers the URL. */
        private Object rewriteUrls(Object value) {
            if (value instanceof String text && text.startsWith("url:")) {
                String url = text.substring("url:".length());
                String key = textureKey(url);
                textures.putIfAbsent(key, new RemoteTexture(key, url));
                return "inline:" + key;
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    result.put(String.valueOf(entry.getKey()), rewriteUrls(entry.getValue()));
                }
                return result;
            }
            if (value instanceof List<?> list) {
                List<Object> result = new ArrayList<>(list.size());
                for (Object entry : list) {
                    result.add(rewriteUrls(entry));
                }
                return result;
            }
            return value;
        }
    }

    private static String textureKey(String url) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int index = 0; index < 8; index++) {
                hex.append(String.format("%02x", hash[index]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 unavailable", exception);
        }
    }
    public static final int MAX_INCLUDE_DEPTH = 8;
    public static final int MAX_FRAGMENT_FILES = 64;
    public static final int MAX_REPEAT_INSTANCES = 256;
    public static final int MAX_ELEMENTS = 512;
    public static final long MAX_FILE_BYTES = 256L * 1024L;

    private static final Set<String> MANIFEST_ONLY_FIELDS = Set.of(
            "id", "surface", "container", "size", "scale", "title", "permission", "theme");
    private static final Set<String> STRUCTURE_FIELDS = Set.of(
            "id", "type", "x", "y", "width", "height", "visible", "enabled", "z",
            "events", "children");

    private PageLoader() {
    }

    /**
     * @param pagesRoot directory the page tree must stay inside (for include containment)
     * @param pageFile  {@code page.yml} or a single-file page
     * @param state     state snapshot used for templates and repeat expansion
     */
    public static UiDocument loadDocument(Path pagesRoot, Path pageFile, Map<String, Object> state)
            throws PageLoadException {
        return load(pagesRoot, pageFile, state).document();
    }

    public static CompiledPage load(Path pagesRoot, Path pageFile, Map<String, Object> state)
            throws PageLoadException {
        Objects.requireNonNull(pagesRoot, "pagesRoot");
        Objects.requireNonNull(pageFile, "pageFile");
        Objects.requireNonNull(state, "state");
        Path root = pagesRoot.toAbsolutePath().normalize();
        Path file = pageFile.toAbsolutePath().normalize();
        if (!file.startsWith(root)) {
            throw new PageLoadException("Page escapes the pages root: " + pageFile);
        }

        Map<String, Object> manifest = readYaml(file);
        List<Map<String, Object>> mergedElements = new ArrayList<>();
        Map<String, Object> mergedState = new LinkedHashMap<>();
        Map<String, Object> mergedFonts = new LinkedHashMap<>();
        Map<String, Object> mergedOptions = new LinkedHashMap<>();
        Set<Path> visited = new LinkedHashSet<>();
        visited.add(file);

        Object includes = manifest.get("includes");
        if (includes instanceof List<?> list) {
            for (Object entry : list) {
                mergeInclude(root, file.getParent(), String.valueOf(entry), mergedElements,
                        mergedState, mergedFonts, mergedOptions, visited, 1);
            }
        }
        appendElements(manifest.get("elements"), mergedElements);

        // Page-level state wins over fragments; the caller's state (the player's session)
        // wins over both, so set_state survives recompiles.
        if (manifest.get("state") instanceof Map<?, ?> pageState) {
            mergedState.putAll(toStringMap(pageState));
        }
        mergedState.putAll(state);
        if (manifest.get("fonts") instanceof Map<?, ?> fonts) {
            mergedFonts.putAll(toStringMap(fonts));
        }
        if (manifest.get("surface_options") instanceof Map<?, ?> options) {
            mergedOptions.putAll(toStringMap(options));
        }

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("state", mergedState);
        String pageId = requireString(manifest, "id");
        BuildContext context = new BuildContext(pageId);
        List<UiElement> elements = buildElements(mergedElements, scope, "", context);

        UiDocument document = new UiDocument(
                intValue(manifest.get("schema"), UiDocument.CURRENT_SCHEMA),
                pageId,
                surface(requireString(manifest, "surface")),
                title(manifest.get("title")),
                optionalString(manifest.get("theme")),
                size(manifest.get("size"), 0),
                size(manifest.get("size"), 1),
                (float) number(manifest.get("scale"), 1.0),
                container(manifest.get("container")),
                mergedOptions,
                parseFonts(mergedFonts),
                elements);

        ValidationResult validation = UiDocumentValidator.validate(document);
        if (!validation.valid()) {
            ValidationIssue issue = validation.errors().get(0);
            throw new PageLoadException(issue.code() + " " + issue.path() + ": " + issue.message());
        }
        return new CompiledPage(document, context.actions, mergedState,
                new ArrayList<>(context.textures.values()));
    }

    private static void mergeInclude(Path root, Path baseDir, String relative,
            List<Map<String, Object>> elements, Map<String, Object> state,
            Map<String, Object> fonts, Map<String, Object> options, Set<Path> visited, int depth)
            throws PageLoadException {
        if (depth > MAX_INCLUDE_DEPTH) {
            throw new PageLoadException("Include depth exceeds " + MAX_INCLUDE_DEPTH
                    + ": " + relative);
        }
        Path file = baseDir.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            throw new PageLoadException("Include escapes the pages root: " + relative);
        }
        if (!visited.add(file)) {
            throw new PageLoadException("Include cycle at " + relative);
        }
        if (visited.size() > MAX_FRAGMENT_FILES) {
            throw new PageLoadException("More than " + MAX_FRAGMENT_FILES + " fragment files");
        }
        Map<String, Object> fragment = readYaml(file);
        for (String forbidden : MANIFEST_ONLY_FIELDS) {
            if (fragment.containsKey(forbidden)) {
                throw new PageLoadException("Fragment " + relative + " must not declare '"
                        + forbidden + "'");
            }
        }
        if (fragment.get("includes") instanceof List<?> nested) {
            for (Object entry : nested) {
                mergeInclude(root, file.getParent(), String.valueOf(entry), elements,
                        state, fonts, options, visited, depth + 1);
            }
        }
        appendElements(fragment.get("elements"), elements);
        if (fragment.get("state") instanceof Map<?, ?> fragmentState) {
            state.putAll(toStringMap(fragmentState));
        }
        if (fragment.get("fonts") instanceof Map<?, ?> fragmentFonts) {
            fonts.putAll(toStringMap(fragmentFonts));
        }
        if (fragment.get("surface_options") instanceof Map<?, ?> fragmentOptions) {
            options.putAll(toStringMap(fragmentOptions));
        }
    }

    private static Map<String, Object> readYaml(Path file) throws PageLoadException {
        try {
            if (!Files.isRegularFile(file)) {
                throw new PageLoadException("Page file missing: " + file.getFileName());
            }
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new PageLoadException("Page file exceeds " + MAX_FILE_BYTES + " bytes: "
                        + file.getFileName());
            }
            String text = quoteEventKeys(Files.readString(file, StandardCharsets.UTF_8));
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(16);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            Object loaded = yaml.load(text);
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new PageLoadException("Page root must be a mapping: " + file.getFileName());
            }
            return toStringMap(map);
        } catch (IOException exception) {
            throw new PageLoadException("Cannot read " + file.getFileName() + ": "
                    + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            throw new PageLoadException("Invalid YAML in " + file.getFileName() + ": "
                    + exception.getMessage(), exception);
        }
    }

    /**
     * YAML 1.1 resolves the bare key {@code on} to boolean true, but {@code on:} is the
     * documented event key. Quoting it at the source keeps SnakeYAML's safe constructor and
     * avoids a custom resolver.
     */
    private static String quoteEventKeys(String text) {
        return text.replaceAll("(?m)^(\\s*)on:(\\s|$)", "$1\"on\":$2");
    }

    private static void appendElements(Object raw, List<Map<String, Object>> target)
            throws PageLoadException {
        if (raw == null) {
            return;
        }
        if (!(raw instanceof List<?> list)) {
            throw new PageLoadException("elements must be a list");
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new PageLoadException("Every element must be a mapping");
            }
            target.add(toStringMap(map));
        }
    }

    private static List<UiElement> buildElements(List<Map<String, Object>> raw,
            Map<String, Object> scope, String idPrefix, BuildContext context)
            throws PageLoadException {
        List<UiElement> elements = new ArrayList<>();
        for (Map<String, Object> element : raw) {
            if ("repeat".equals(String.valueOf(element.get("type")))) {
                elements.addAll(expandRepeat(element, scope, idPrefix, context));
                continue;
            }
            elements.add(buildElement(element, scope, idPrefix, 0, context));
            if (elements.size() > MAX_ELEMENTS) {
                throw new PageLoadException("Element count exceeds " + MAX_ELEMENTS);
            }
        }
        return elements;
    }

    private static List<UiElement> expandRepeat(Map<String, Object> repeat,
            Map<String, Object> scope, String idPrefix, BuildContext context)
            throws PageLoadException {
        String repeatId = String.valueOf(repeat.get("id"));
        Object source = resolveTemplate(repeat.get("source"), scope);
        if (!(source instanceof List<?> items)) {
            throw new PageLoadException("repeat '" + repeatId + "' source is not a list");
        }
        int max = (int) number(repeat.get("max"), MAX_REPEAT_INSTANCES);
        max = Math.min(Math.max(0, max), MAX_REPEAT_INSTANCES);
        String alias = optionalString(repeat.get("as"));
        Object rawChildren = repeat.get("children");
        if (!(rawChildren instanceof List<?> children)) {
            throw new PageLoadException("repeat '" + repeatId + "' needs children");
        }

        int gap = (int) number(repeat.get("gap"), 0);
        int step = firstChildHeight(children) + gap;

        List<UiElement> result = new ArrayList<>();
        int count = Math.min(items.size(), max);
        for (int index = 0; index < count; index++) {
            Map<String, Object> instanceScope = new LinkedHashMap<>(scope);
            instanceScope.put(alias == null ? "item" : alias, items.get(index));
            instanceScope.put("i", index);
            String instancePrefix = idPrefix + repeatId + "[" + index + "].";
            int yOffset = index * step;
            for (Object child : children) {
                if (!(child instanceof Map<?, ?> childMap)) {
                    throw new PageLoadException("repeat child must be a mapping");
                }
                Map<String, Object> materialised = toStringMap(childMap);
                if ("repeat".equals(String.valueOf(materialised.get("type")))) {
                    result.addAll(expandRepeat(materialised, instanceScope, instancePrefix,
                            context));
                } else {
                    result.add(buildElement(materialised, instanceScope, instancePrefix,
                            yOffset, context));
                }
            }
        }
        return result;
    }

    /** Height of the first child, used to stack repeat instances when no layout is set. */
    private static int firstChildHeight(List<?> children) {
        for (Object child : children) {
            if (child instanceof Map<?, ?> map && map.get("height") != null) {
                return (int) number(map.get("height"), 0);
            }
            if (child instanceof Map<?, ?>) {
                return 0;
            }
        }
        return 0;
    }

    private static UiElement buildElement(Map<String, Object> raw, Map<String, Object> scope,
            String idPrefix, int yOffset, BuildContext context) throws PageLoadException {
        Object rawId = raw.get("id");
        if (!(rawId instanceof String id) || id.isBlank()) {
            throw new PageLoadException("Element is missing an id");
        }
        String type = String.valueOf(raw.get("type"));
        if (type == null || type.isBlank() || "null".equals(type)) {
            throw new PageLoadException("Element '" + id + "' is missing a type");
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            // Action bodies stay on the server; the document only carries events map.
            if (!STRUCTURE_FIELDS.contains(entry.getKey()) && !"on".equals(entry.getKey())) {
                attributes.put(entry.getKey(),
                        context.rewriteUrls(applyTemplate(entry.getValue(), scope)));
            }
        }
        List<UiElement> children = new ArrayList<>();
        if (raw.get("children") instanceof List<?> childList) {
            List<Map<String, Object>> childMaps = new ArrayList<>();
            for (Object child : childList) {
                if (!(child instanceof Map<?, ?> childMap)) {
                    throw new PageLoadException("Element '" + id + "' child must be a mapping");
                }
                childMaps.add(toStringMap(childMap));
            }
            children = buildElements(childMaps, scope, idPrefix + id + ".", context);
        }
        String fullId = idPrefix + id;
        Map<String, Integer> events = compileEvents(raw.get("on"), fullId, scope, context);

        return new UiElement(
                fullId,
                type,
                (int) number(raw.get("x"), 0),
                (int) number(raw.get("y"), 0) + yOffset,
                raw.get("width") == null ? null : String.valueOf(raw.get("width")),
                raw.get("height") == null ? null : String.valueOf(raw.get("height")),
                !Boolean.FALSE.equals(raw.get("visible")),
                !Boolean.FALSE.equals(raw.get("enabled")),
                (int) number(raw.get("z"), 0),
                events,
                children,
                attributes);
    }

    /**
     * Compiles {@code on: {event: [actions]}} into the document's {@code events} map
     * (event -> actionId) and keeps the action bodies for the server.
     */
    private static Map<String, Integer> compileEvents(Object raw, String elementId,
            Map<String, Object> scope, BuildContext context) throws PageLoadException {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> onMap)) {
            throw new PageLoadException("Element '" + elementId + "' has a non-mapping 'on'");
        }
        Map<String, Integer> events = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : onMap.entrySet()) {
            String event = String.valueOf(entry.getKey());
            Object rawActions = entry.getValue();
            if (rawActions == null) {
                continue;
            }
            // Runtime payload placeholders such as ${checked} must survive compilation.
            Object resolved = applyTemplatePreserving(rawActions, scope);
            List<PageAction> actions = new ArrayList<>();
            if (resolved instanceof List<?> list) {
                for (Object item : list) {
                    actions.add(toAction(item, elementId));
                }
            } else {
                actions.add(toAction(resolved, elementId));
            }
            int actionId = context.actionId(elementId, event);
            context.actions.put(actionId, actions);
            events.put(event, actionId);
        }
        return events;
    }

    private static PageAction toAction(Object raw, String elementId) throws PageLoadException {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new PageLoadException("Action in '" + elementId + "' must be a mapping");
        }
        Object type = map.get("action");
        if (!(type instanceof String name) || name.isBlank()) {
            throw new PageLoadException("Action in '" + elementId + "' is missing 'action'");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!"action".equals(String.valueOf(entry.getKey()))) {
                params.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return new PageAction(name, params);
    }

    /** Replaces {@code ${state.x}}, {@code ${item.field}} and {@code ${i}} in strings. */
    static Object applyTemplate(Object value, Map<String, Object> scope) {
        return applyTemplate(value, scope, false);
    }

    /** Like {@link #applyTemplate} but leaves unknown {@code ${...}} for runtime payloads. */
    static Object applyTemplatePreserving(Object value, Map<String, Object> scope) {
        return applyTemplate(value, scope, true);
    }

    private static Object applyTemplate(Object value, Map<String, Object> scope,
            boolean keepUnknown) {
        if (value instanceof String text) {
            if (text.contains("${")) {
                return interpolate(text, scope, keepUnknown);
            }
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()),
                        applyTemplate(entry.getValue(), scope, keepUnknown));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object entry : list) {
                result.add(applyTemplate(entry, scope, keepUnknown));
            }
            return result;
        }
        return value;
    }

    static Object resolveTemplate(Object value, Map<String, Object> scope) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
                Object resolved = lookup(trimmed.substring(2, trimmed.length() - 1), scope);
                return resolved == null ? text : resolved;
            }
            return applyTemplate(text, scope);
        }
        return applyTemplate(value, scope);
    }

    private static String interpolate(String text, Map<String, Object> scope) {
        return interpolate(text, scope, false);
    }

    private static String interpolate(String text, Map<String, Object> scope,
            boolean keepUnknown) {
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        while (true) {
            int start = text.indexOf("${", cursor);
            if (start < 0) {
                result.append(text, cursor, text.length());
                break;
            }
            int end = text.indexOf('}', start + 2);
            if (end < 0) {
                result.append(text, cursor, text.length());
                break;
            }
            result.append(text, cursor, start);
            String expression = text.substring(start + 2, end);
            Object value = lookup(expression, scope);
            if (value == null && keepUnknown) {
                result.append("${").append(expression).append('}');
            } else {
                result.append(value == null ? "" : String.valueOf(value));
            }
            cursor = end + 1;
        }
        return result.toString();
    }

    private static Object lookup(String path, Map<String, Object> scope) {
        Object current = scope;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list) {
                try {
                    int index = Integer.parseInt(part);
                    current = index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException exception) {
                    return null;
                }
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static SurfaceType surface(String value) throws PageLoadException {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "custom_screen" -> SurfaceType.CUSTOM_SCREEN;
            case "container" -> SurfaceType.CONTAINER;
            case "player_inventory" -> SurfaceType.PLAYER_INVENTORY;
            case "hud" -> SurfaceType.HUD;
            case "tooltip" -> SurfaceType.TOOLTIP;
            case "boss_bar" -> SurfaceType.BOSS_BAR;
            default -> throw new PageLoadException("Unknown surface: " + value);
        };
    }

    private static TextRuns title(Object raw) throws PageLoadException {
        if (raw instanceof String text) {
            return TextRuns.plain(text);
        }
        if (raw instanceof Map<?, ?> map && map.get("runs") instanceof List<?> runs) {
            List<TextRun> result = new ArrayList<>(runs.size());
            for (Object run : runs) {
                if (!(run instanceof Map<?, ?> runMap)) {
                    throw new PageLoadException("title.runs entries must be mappings");
                }
                result.add(new TextRun(String.valueOf(runMap.get("text")),
                        optionalString(runMap.get("color")),
                        Boolean.TRUE.equals(runMap.get("bold")),
                        Boolean.TRUE.equals(runMap.get("italic")),
                        Boolean.TRUE.equals(runMap.get("underline")),
                        Boolean.TRUE.equals(runMap.get("strikethrough")),
                        Boolean.TRUE.equals(runMap.get("obfuscated")),
                        optionalString(runMap.get("font"))));
            }
            return new TextRuns(result);
        }
        throw new PageLoadException("title must be a string or {runs: [...]}");
    }

    private static int size(Object raw, int index) throws PageLoadException {
        if (!(raw instanceof List<?> list) || list.size() != 2) {
            throw new PageLoadException("size must be [width, height]");
        }
        return (int) number(list.get(index), 0);
    }

    private static ContainerSpec container(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        return new ContainerSpec((int) number(map.get("rows"), 0),
                map.get("interaction") == null ? "managed" : String.valueOf(map.get("interaction")));
    }

    private static Map<String, FontSpec> parseFonts(Map<String, Object> raw)
            throws PageLoadException {
        Map<String, FontSpec> fonts = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> map)) {
                throw new PageLoadException("font '" + entry.getKey() + "' must be a mapping");
            }
            fonts.put(entry.getKey(), new FontSpec(
                    stringOr(map.get("provider"), "nanovg_ttf"),
                    stringOr(map.get("source"), "local"),
                    stringOr(map.get("id"), entry.getKey()),
                    optionalString(map.get("fallback")),
                    (int) number(map.get("line_gap"), 0),
                    Boolean.TRUE.equals(map.get("shadow"))));
        }
        return fonts;
    }

    private static String requireString(Map<String, Object> map, String key)
            throws PageLoadException {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new PageLoadException("Page is missing '" + key + "'");
        }
        return text;
    }

    private static String optionalString(Object value) {
        return value instanceof String text ? text : null;
    }

    private static String stringOr(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException exception) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int intValue(Object value, int fallback) {
        return (int) number(value, fallback);
    }

    private static Map<String, Object> toStringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
