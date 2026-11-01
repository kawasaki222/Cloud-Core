package com.cloudcore.bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * Remembers the SHA-256 of every page file the watcher has seen, so a save that did not change
 * the bytes does not trigger a recompile.
 *
 * <p>Editors and tools rewrite files on save even when the content is identical (touch, format
 * on save with the same result, git checkout of an unchanged branch). Without this, every such
 * save reloads and re-pushes the page to every player looking at it.
 *
 * <p>A file that disappears is forgotten, so recreating it counts as a change. Thread-safe:
 * hashes are computed on the watcher thread and pruned on the main thread.
 */
final class PageHashes {
    private final Map<String, String> hashes = new HashMap<>();

    /**
     * @return the paths whose content actually changed; paths that cannot be read are returned
     *         as changed (a broken file must still be reported by the reload)
     */
    synchronized Set<String> changed(Path root, Set<String> relativePaths) {
        Set<String> changed = new HashSet<>();
        for (String relative : relativePaths) {
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root.normalize())) {
                continue;
            }
            if (!Files.isRegularFile(file)) {
                // Deleted: forget it so a later recreate is a change.
                hashes.remove(relative);
                changed.add(relative);
                continue;
            }
            String hash = hashOf(file);
            if (hash == null) {
                changed.add(relative);
                continue;
            }
            String previous = hashes.put(relative, hash);
            if (!hash.equals(previous)) {
                changed.add(relative);
            }
        }
        return changed;
    }

    /** Drops hashes for files that no longer exist, so the map cannot grow forever. */
    synchronized void prune(Path root) {
        hashes.keySet().removeIf(relative -> !Files.isRegularFile(root.resolve(relative)));
    }

    synchronized int size() {
        return hashes.size();
    }

    static String hashOf(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException | NoSuchAlgorithmException exception) {
            return null;
        }
    }
}
