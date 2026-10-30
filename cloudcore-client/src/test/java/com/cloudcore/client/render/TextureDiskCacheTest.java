package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextureDiskCacheTest {
    @TempDir
    Path dir;

    @Test
    void writesAndReadsBytes() throws Exception {
        TextureDiskCache cache = new TextureDiskCache(dir, 1024);
        cache.write("abc123", new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, cache.read("abc123"));
        assertNull(cache.read("missing"));
    }

    @Test
    void sanitisesKeysIntoOneFileName() throws Exception {
        TextureDiskCache cache = new TextureDiskCache(dir, 1024);
        cache.write("../../evil key", new byte[]{9});
        assertNotNull(cache.read("../../evil key"));
        try (var files = Files.list(dir)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().contains("..")));
        }
    }

    @Test
    void trimsOldestEntriesToTheByteBudget() throws Exception {
        TextureDiskCache cache = new TextureDiskCache(dir, 100);
        cache.write("old", new byte[60]);
        Files.setLastModifiedTime(cache.fileFor("old"), FileTime.from(Instant.EPOCH));
        cache.write("new", new byte[60]);

        assertNull(cache.read("old"));
        assertNotNull(cache.read("new"));
    }

    @Test
    void clearRemovesCachedFiles() throws Exception {
        TextureDiskCache cache = new TextureDiskCache(dir, 1024);
        cache.write("one", new byte[]{1});
        cache.clear();
        assertNull(cache.read("one"));
    }
}
