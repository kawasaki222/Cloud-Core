package com.cloudcore.server.state;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One JSON file per player under {@code root}: {@code <uuid>.json}.
 *
 * <p>Writes go to a temporary file in the same directory and are then moved into place, so a
 * crash never leaves a half-written state file. Reads reject files larger than
 * {@link #MAX_FILE_BYTES} and unknown versions instead of guessing.
 */
public final class FilePlayerStateStore implements PlayerStateStore {
    public static final int VERSION = 1;
    public static final long MAX_FILE_BYTES = 1024L * 1024L;
    public static final int MAX_PAGES = 64;
    public static final int MAX_KEYS_PER_PAGE = 256;
    public static final int MAX_STRING_LENGTH = 4096;

    private final Path root;

    public FilePlayerStateStore(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    @Override
    public Map<String, Map<String, Object>> load(UUID player) throws IOException {
        Path file = fileFor(player);
        if (Files.notExists(file)) {
            return Map.of();
        }
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw new IOException("state file for " + player + " is " + size + " bytes (max "
                    + MAX_FILE_BYTES + ")");
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(json);
        } catch (RuntimeException exception) {
            throw new IOException("state file for " + player + " is not valid JSON", exception);
        }
        if (!rootElement.isJsonObject()) {
            throw new IOException("state file for " + player + " must be a JSON object");
        }
        JsonObject object = rootElement.getAsJsonObject();
        int version = object.has("version") && object.get("version").isJsonPrimitive()
                ? object.get("version").getAsInt() : 0;
        if (version > VERSION) {
            throw new IOException("state file for " + player + " uses version " + version
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

    @Override
    public void save(UUID player, Map<String, Map<String, Object>> state) throws IOException {
        Objects.requireNonNull(player, "player");
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

        Files.createDirectories(root);
        Path file = fileFor(player);
        Path temporary = root.resolve(player + ".json.tmp");
        Files.writeString(temporary, rootObject.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Path fileFor(UUID player) {
        return root.resolve(player + ".json");
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
