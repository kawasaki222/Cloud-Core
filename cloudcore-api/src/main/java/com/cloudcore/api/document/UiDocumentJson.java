package com.cloudcore.api.document;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.page.PageLoadException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Decodes the JSON carried by {@code UI_DEFINITION} into the shared document model.
 *
 * <p>This parser is intentionally strict about structural fields and keeps unknown
 * element fields in {@link UiElement#attributes()} for forward compatibility.
 */
public final class UiDocumentJson {
    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder()
            .serializeNulls()
            .create();
    private static final Set<String> ELEMENT_STRUCTURE_FIELDS = Set.of(
            "id", "type", "x", "y", "width", "height", "visible", "enabled", "z",
            "events", "children");

    private UiDocumentJson() {
    }

    /**
     * Serialises a compiled document back to the JSON the client receives. Round-trips with
     * {@link #parse(String)}; the output contains no actions, permissions or raw URLs.
     */
    public static String write(UiDocument document) {
        Objects.requireNonNull(document, "document");
        JsonObject root = new JsonObject();
        root.addProperty("schema", document.schema());
        root.addProperty("id", document.id());
        root.addProperty("surface", document.surface().name().toLowerCase(Locale.ROOT));
        root.add("title", new JsonPrimitive(document.title().plainText()));
        if (document.theme() != null) {
            root.addProperty("theme", document.theme());
        }
        JsonArray size = new JsonArray();
        size.add(document.width());
        size.add(document.height());
        root.add("size", size);
        if (document.scale() != 1.0f) {
            root.addProperty("scale", document.scale());
        }
        if (document.container() != null) {
            JsonObject container = new JsonObject();
            container.addProperty("rows", document.container().rows());
            container.addProperty("interaction", document.container().interaction());
            container.addProperty("hide_player_inventory",
                    document.container().hidePlayerInventory());
            container.addProperty("allow_player_inventory",
                    document.container().allowPlayerInventory());
            root.add("container", container);
        }
        if (!document.surfaceOptions().isEmpty()) {
            root.add("surface_options", GSON.toJsonTree(document.surfaceOptions()));
        }
        if (!document.fonts().isEmpty()) {
            JsonObject fonts = new JsonObject();
            for (Map.Entry<String, FontSpec> entry : document.fonts().entrySet()) {
                FontSpec spec = entry.getValue();
                JsonObject font = new JsonObject();
                font.addProperty("provider", spec.provider());
                font.addProperty("source", spec.source());
                font.addProperty("id", spec.id());
                if (spec.fallback() != null) {
                    font.addProperty("fallback", spec.fallback());
                }
                if (spec.lineGap() != 0) {
                    font.addProperty("line_gap", spec.lineGap());
                }
                if (spec.shadow()) {
                    font.addProperty("shadow", true);
                }
                fonts.add(entry.getKey(), font);
            }
            root.add("fonts", fonts);
        }
        JsonArray elements = new JsonArray();
        for (UiElement element : document.elements()) {
            elements.add(writeElement(element));
        }
        root.add("elements", elements);
        return GSON.toJson(root);
    }

    private static JsonElement writeElement(UiElement element) {
        JsonObject object = new JsonObject();
        object.addProperty("id", element.id());
        object.addProperty("type", element.type());
        object.addProperty("x", element.x());
        object.addProperty("y", element.y());
        if (element.width() != null) {
            object.addProperty("width", element.width());
        }
        if (element.height() != null) {
            object.addProperty("height", element.height());
        }
        if (!element.visible()) {
            object.addProperty("visible", false);
        }
        if (!element.enabled()) {
            object.addProperty("enabled", false);
        }
        if (element.z() != 0) {
            object.addProperty("z", element.z());
        }
        for (Map.Entry<String, Object> entry : element.attributes().entrySet()) {
            object.add(entry.getKey(), GSON.toJsonTree(entry.getValue()));
        }
        if (!element.events().isEmpty()) {
            JsonObject events = new JsonObject();
            for (Map.Entry<String, Integer> entry : element.events().entrySet()) {
                events.addProperty(entry.getKey(), entry.getValue());
            }
            object.add("events", events);
        }
        if (!element.children().isEmpty()) {
            JsonArray children = new JsonArray();
            for (UiElement child : element.children()) {
                children.add(writeElement(child));
            }
            object.add("children", children);
        }
        return object;
    }

    public static UiDocument parse(byte[] json) throws PageLoadException {
        Objects.requireNonNull(json, "json");
        return parse(new String(json, StandardCharsets.UTF_8));
    }

    public static UiDocument parse(String json) throws PageLoadException {
        Objects.requireNonNull(json, "json");
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                throw new PageLoadException("UI document root must be an object");
            }
            return parseDocument(root.getAsJsonObject());
        } catch (PageLoadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PageLoadException("Invalid UI document: " + exception.getMessage(), exception);
        }
    }

    private static UiDocument parseDocument(JsonObject object) throws PageLoadException {
        int schema = requireInt(object, "schema", "document");
        String id = requireString(object, "id", "document");
        SurfaceType surface = parseSurface(requireString(object, "surface", "document"));
        TextRuns title = parseTitle(object.get("title"));
        String theme = optionalString(object, "theme", "document");

        JsonArray size = requireArray(object, "size", "document");
        if (size.size() != 2) {
            throw new PageLoadException("document.size must contain exactly two integers");
        }
        int width = intValue(size.get(0), "document.size[0]");
        int height = intValue(size.get(1), "document.size[1]");
        float scale = optionalFloat(object, "scale", 1.0f, "document");

        ContainerSpec container = null;
        if (present(object, "container")) {
            container = parseContainer(object.getAsJsonObject("container"), "document.container");
        }
        Map<String, Object> surfaceOptions = Map.of();
        if (present(object, "surface_options")) {
            surfaceOptions = toMap(object.getAsJsonObject("surface_options"), "document.surface_options");
        }
        Map<String, FontSpec> fonts = parseFonts(object.get("fonts"));
        List<UiElement> elements = parseElements(
                requireArray(object, "elements", "document"), "document.elements");

        return new UiDocument(schema, id, surface, title, theme, width, height, scale,
                container, surfaceOptions, fonts, elements);
    }

    private static SurfaceType parseSurface(String value) throws PageLoadException {
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

    private static TextRuns parseTitle(JsonElement element) throws PageLoadException {
        if (element == null || element.isJsonNull()) {
            throw new PageLoadException("document.title is required");
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return TextRuns.plain(element.getAsString());
        }
        if (!element.isJsonObject()) {
            throw new PageLoadException("document.title must be an object or string");
        }
        JsonArray runs = requireArray(element.getAsJsonObject(), "runs", "document.title");
        List<TextRun> result = new ArrayList<>(runs.size());
        for (int index = 0; index < runs.size(); index++) {
            String path = "document.title.runs[" + index + "]";
            JsonObject run = requireObject(runs.get(index), path);
            result.add(new TextRun(
                    requireString(run, "text", path),
                    optionalString(run, "color", path),
                    optionalBoolean(run, "bold", false, path),
                    optionalBoolean(run, "italic", false, path),
                    optionalBoolean(run, "underline", false, path),
                    optionalBoolean(run, "strikethrough", false, path),
                    optionalBoolean(run, "obfuscated", false, path),
                    optionalString(run, "font", path)));
        }
        return new TextRuns(result);
    }

    private static ContainerSpec parseContainer(JsonObject object, String path)
            throws PageLoadException {
        return new ContainerSpec(
                requireInt(object, "rows", path),
                optionalString(object, "interaction", path, "managed"),
                optionalBoolean(object, "hide_player_inventory", false, path),
                optionalBoolean(object, "allow_player_inventory", false, path));
    }

    private static Map<String, FontSpec> parseFonts(JsonElement element) throws PageLoadException {
        if (element == null || element.isJsonNull()) {
            return Map.of();
        }
        JsonObject object = requireObject(element, "document.fonts");
        Map<String, FontSpec> fonts = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String path = "document.fonts." + entry.getKey();
            JsonObject font = requireObject(entry.getValue(), path);
            fonts.put(entry.getKey(), new FontSpec(
                    requireString(font, "provider", path),
                    optionalString(font, "source", path, "local"),
                    requireString(font, "id", path),
                    optionalString(font, "fallback", path),
                    optionalInt(font, "line_gap", 0, path),
                    optionalBoolean(font, "shadow", false, path)));
        }
        return fonts;
    }

    private static List<UiElement> parseElements(JsonElement element, String path)
            throws PageLoadException {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        JsonArray array = requireArray(element, path);
        List<UiElement> elements = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            String childPath = path + "[" + index + "]";
            elements.add(parseElement(requireObject(array.get(index), childPath), childPath));
        }
        return elements;
    }

    private static UiElement parseElement(JsonObject object, String path) throws PageLoadException {
        Map<String, Integer> events = parseEvents(object.get("events"), path + ".events");
        List<UiElement> children = parseElements(object.get("children"), path + ".children");
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!ELEMENT_STRUCTURE_FIELDS.contains(entry.getKey())) {
                attributes.put(entry.getKey(), toJava(entry.getValue()));
            }
        }
        return new UiElement(
                requireString(object, "id", path),
                requireString(object, "type", path),
                optionalInt(object, "x", 0, path),
                optionalInt(object, "y", 0, path),
                optionalDimension(object, "width", path),
                optionalDimension(object, "height", path),
                optionalBoolean(object, "visible", true, path),
                optionalBoolean(object, "enabled", true, path),
                optionalInt(object, "z", 0, path),
                events,
                children,
                attributes);
    }

    private static Map<String, Integer> parseEvents(JsonElement element, String path)
            throws PageLoadException {
        if (element == null || element.isJsonNull()) {
            return Map.of();
        }
        JsonObject object = requireObject(element, path);
        Map<String, Integer> events = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            events.put(entry.getKey(), intValue(entry.getValue(), path + "." + entry.getKey()));
        }
        return events;
    }

    private static Map<String, Object> toMap(JsonObject object, String path)
            throws PageLoadException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            result.put(entry.getKey(), toJava(entry.getValue()));
        }
        return result;
    }

    private static Object toJava(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                return primitive.getAsNumber();
            }
            return primitive.getAsString();
        }
        if (element.isJsonArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonElement child : element.getAsJsonArray()) {
                values.add(toJava(child));
            }
            return values;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            values.put(entry.getKey(), toJava(entry.getValue()));
        }
        return values;
    }

    private static String optionalDimension(JsonObject object, String key, String path)
            throws PageLoadException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive()) {
            throw new PageLoadException(path + "." + key + " must be a number or string");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber() && !primitive.isString()) {
            throw new PageLoadException(path + "." + key + " must be a number or string");
        }
        return primitive.getAsString();
    }

    private static boolean present(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && !element.isJsonNull();
    }

    private static JsonObject requireObject(JsonElement element, String path)
            throws PageLoadException {
        if (element == null || !element.isJsonObject()) {
            throw new PageLoadException(path + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject object, String key, String path)
            throws PageLoadException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray()) {
            throw new PageLoadException(path + "." + key + " must be an array");
        }
        return element.getAsJsonArray();
    }

    private static JsonArray requireArray(JsonElement element, String path)
            throws PageLoadException {
        if (element == null || !element.isJsonArray()) {
            throw new PageLoadException(path + " must be an array");
        }
        return element.getAsJsonArray();
    }

    private static String requireString(JsonObject object, String key, String path)
            throws PageLoadException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new PageLoadException(path + "." + key + " must be a string");
        }
        return element.getAsString();
    }

    private static String optionalString(JsonObject object, String key, String path)
            throws PageLoadException {
        return optionalString(object, key, path, null);
    }

    private static String optionalString(JsonObject object, String key, String path, String fallback)
            throws PageLoadException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new PageLoadException(path + "." + key + " must be a string");
        }
        return element.getAsString();
    }

    private static int requireInt(JsonObject object, String key, String path)
            throws PageLoadException {
        JsonElement element = object.get(key);
        if (element == null) {
            throw new PageLoadException(path + "." + key + " is required");
        }
        return intValue(element, path + "." + key);
    }

    private static int intValue(JsonElement element, String path) throws PageLoadException {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new PageLoadException(path + " must be an integer");
        }
        return element.getAsInt();
    }

    private static int optionalInt(JsonObject object, String key, int fallback, String path)
            throws PageLoadException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        return intValue(element, path + "." + key);
    }

    private static float optionalFloat(JsonObject object, String key, float fallback, String path)
            throws PageLoadException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new PageLoadException(path + "." + key + " must be a number");
        }
        return element.getAsFloat();
    }

    private static boolean optionalBoolean(JsonObject object, String key, boolean fallback,
            String path) throws PageLoadException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new PageLoadException(path + "." + key + " must be a boolean");
        }
        return element.getAsBoolean();
    }
}
