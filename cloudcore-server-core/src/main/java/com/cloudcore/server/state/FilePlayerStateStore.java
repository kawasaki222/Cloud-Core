package com.cloudcore.server.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    public static final int VERSION = StateJson.VERSION;
    public static final long MAX_FILE_BYTES = 1024L * 1024L;
    public static final int MAX_PAGES = StateJson.MAX_PAGES;
    public static final int MAX_KEYS_PER_PAGE = StateJson.MAX_KEYS_PER_PAGE;
    public static final int MAX_STRING_LENGTH = StateJson.MAX_STRING_LENGTH;

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
        return StateJson.decode(json, player.toString());
    }

    @Override
    public void save(UUID player, Map<String, Map<String, Object>> state) throws IOException {
        Objects.requireNonNull(player, "player");
        String json = StateJson.encode(state);
        Files.createDirectories(root);
        Path file = fileFor(player);
        Path temporary = root.resolve(player + ".json.tmp");
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
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
}
