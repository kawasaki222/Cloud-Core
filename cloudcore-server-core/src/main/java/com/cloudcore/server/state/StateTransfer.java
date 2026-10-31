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
import java.util.Map;
import java.util.UUID;

/**
 * JSON export/import for page state. The file is backend-neutral:
 *
 * <pre>{@code
 * {"version":1,"players":{"<uuid>":{"version":1,"pages":{...}}}}
 * }</pre>
 *
 * <p>Import replaces the target player's state with the exported document. It is intended for
 * maintenance windows and migrations; there is no merge rule to guess.
 */
public final class StateTransfer {
    public static final int VERSION = 1;
    public static final long MAX_FILE_BYTES = 256L * 1024L * 1024L;

    private StateTransfer() {
    }

    /** @return number of players written */
    public static int export(PlayerStateStore source, Path file) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject players = new JsonObject();
        int count = 0;
        for (UUID player : source.players()) {
            Map<String, Map<String, Object>> state = source.load(player);
            players.add(player.toString(), JsonParser.parseString(StateJson.encode(state)));
            count++;
        }
        root.add("players", players);
        writeAtomically(file, root.toString());
        return count;
    }

    /** @return number of players imported */
    public static int importFile(PlayerStateStore target, Path file) throws IOException {
        if (Files.notExists(file)) {
            throw new IOException("export file does not exist: " + file);
        }
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("export file is larger than " + MAX_FILE_BYTES + " bytes");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            throw new IOException("export file is not valid JSON", exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("export file must be a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        int version = root.has("version") && root.get("version").isJsonPrimitive()
                ? root.get("version").getAsInt() : 0;
        if (version > VERSION) {
            throw new IOException("export version " + version + " is newer than " + VERSION);
        }
        if (!root.has("players") || !root.get("players").isJsonObject()) {
            throw new IOException("export file has no players object");
        }
        int count = 0;
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("players").entrySet()) {
            UUID player;
            try {
                player = UUID.fromString(entry.getKey());
            } catch (IllegalArgumentException exception) {
                throw new IOException("invalid player id in export: " + entry.getKey());
            }
            if (!entry.getValue().isJsonObject()) {
                throw new IOException("state for " + player + " must be an object");
            }
            Map<String, Map<String, Object>> state = StateJson.decode(
                    entry.getValue().toString(), player.toString());
            target.save(player, state);
            count++;
        }
        return count;
    }

    private static void writeAtomically(Path file, String json) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
