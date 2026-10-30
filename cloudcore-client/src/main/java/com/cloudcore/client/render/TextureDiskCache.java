package com.cloudcore.client.render;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Best-effort disk cache for URL texture bytes. Keys are already content hashes from the
 * server document, so the file name is safe after a small sanitisation pass.
 *
 * <p>Writes go through a temporary file in the same directory. The cache is optional: a read
 * or write failure is reported by the caller and never blocks rendering.
 */
final class TextureDiskCache {
    private static final long DEFAULT_MAX_BYTES = 128L * 1024L * 1024L;

    private final Path root;
    private final long maxBytes;

    TextureDiskCache(Path root, long maxBytes) {
        this.root = root;
        this.maxBytes = maxBytes <= 0 ? DEFAULT_MAX_BYTES : maxBytes;
    }

    /** @return cached bytes, or {@code null} when absent or unreadable */
    byte[] read(String key) {
        Path file = fileFor(key);
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (IOException exception) {
            return null;
        }
    }

    void write(String key, byte[] data) throws IOException {
        Files.createDirectories(root);
        Path file = fileFor(key);
        Path temporary = root.resolve(file.getFileName() + ".tmp");
        Files.write(temporary, data);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
        trim();
    }

    void clear() {
        try (var files = Files.list(root)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // A stale cache file is harmless; the next write will retry.
                }
            });
        } catch (IOException ignored) {
            // The cache directory may not exist yet.
        }
    }

    Path fileFor(String key) {
        String safe = key == null ? "unknown" : key.replaceAll("[^a-zA-Z0-9_-]", "_");
        return root.resolve(safe + ".bin");
    }

    private void trim() {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".bin"))
                    .forEach(files::add);
        } catch (IOException exception) {
            return;
        }
        long total = 0;
        for (Path file : files) {
            try {
                total += Files.size(file);
            } catch (IOException ignored) {
                // Skip a file that vanished during the scan.
            }
        }
        if (total <= maxBytes) {
            return;
        }
        files.sort(Comparator.comparingLong(file -> {
            try {
                return Files.getLastModifiedTime(file).toMillis();
            } catch (IOException exception) {
                return 0L;
            }
        }));
        for (Path file : files) {
            if (total <= maxBytes) {
                break;
            }
            try {
                long size = Files.size(file);
                if (Files.deleteIfExists(file)) {
                    total -= size;
                }
            } catch (IOException ignored) {
                // Keep the entry; a later trim can retry.
            }
        }
    }

}
