package com.cloudcore.server.state;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The shared on-disk/wire shape for page state: {@code {version, pages: {page: {key: value}}}}.
 * Both the JSON file store and the SQL stores use this codec so their limits and value
 * coercions cannot drift apart.
 */
public final class StateJson {
    public static final int VERSION = 1;
    public static final int MAX_PAGES = 64;
    public static final int MAX_KEYS_PER_PAGE = 256;
    public static final int MAX_STRING_LENGTH = 4096;

    private StateJson() {
    }

    public static String encode(Map<String, Map<String, Object>> state) {
        JsonObject rootObject = new JsonObject();
        rootObject.addProperty("version", VERSION);
        JsonObject pages = new JsonObject();
        int pageCount = 0;
        for (Map.Entry<String, Map<String, Object>> page : state.entrySet()) {
            if (pageCount++ >= MAX_PAGES) {
                break;
            }
            JsonObject values = new JsonObject();
            int keyCount = 0;
            for (Map.Entry<String, Object> entry : page.getValue().entrySet()) {
                if (keyCount++ >= MAX_KEYS_PER_PAGE) {
                    break;
                }
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }
                if (value instanceof Number number) {
                    values.addProperty(entry.getKey(), number.doubleValue());
                } else if (value instanceof Boolean flag) {
                    values.addProperty(entry.getKey(), flag);
                } else {
                    String text = String.valueOf(value);
                    values.addProperty(entry.getKey(), text.length() > MAX_STRING_LENGTH
                            ? text.substring(0, MAX_STRING_LENGTH) : text);
                }
            }
            pages.add(page.getKey(), values);
        }
        rootObject.add("pages", pages);
        return rootObject.toString();
    }

    public static Map<String, Map<String, Object>> decode(String json, String context)
            throws IOException {
        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(json);
        } catch (RuntimeException exception) {
            throw new IOException("state for " + context + " is not valid JSON", exception);
        }
        if (!rootElement.isJsonObject()) {
            throw new IOException("state for " + context + " must be a JSON object");
        }
        JsonObject object = rootElement.getAsJsonObject();
        int version = object.has("version") && object.get("version").isJsonPrimitive()
                ? object.get("version").getAsInt() : 0;
        if (version > VERSION) {
            throw new IOException("state for " + context + " uses version " + version
                    + " (this build understands " + VERSION + ")");
        }
        Map<String, Map<String, Object>> pages = new LinkedHashMap<>();
        if (!object.has("pages") || !object.get("pages").isJsonObject()) {
            return pages;
        }
        for (Map.Entry<String, JsonElement> page : object.getAsJsonObject("pages").entrySet()) {
            if (pages.size() >= MAX_PAGES) {
                break;
            }
            if (!page.getValue().isJsonObject()) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry
                    : page.getValue().getAsJsonObject().entrySet()) {
                if (values.size() >= MAX_KEYS_PER_PAGE) {
                    break;
                }
                Object value = toJava(entry.getValue());
                if (value != null) {
                    values.put(entry.getKey(), value);
                }
            }
            pages.put(page.getKey(), values);
        }
        return pages;
    }

    private static Object toJava(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        var primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsDouble();
        }
        return primitive.getAsString();
    }
}
