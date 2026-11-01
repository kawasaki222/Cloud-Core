package com.cloudcore.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A save that leaves the bytes alone must not reload the page: editors write on every save,
 * and each reload recompiles and re-pushes the document to every player looking at it.
 */
class PageHashesTest {

    @TempDir
    Path pages;

    private PageHashes seed() throws IOException {
        write("shop.yml", "id: shop\n");
        PageHashes hashes = new PageHashes();
        hashes.changed(pages, Set.of("shop.yml"));
        return hashes;
    }

    @Test
    void anUnchangedSaveIsNotAChange() throws IOException {
        PageHashes hashes = seed();
        write("shop.yml", "id: shop\n");

        assertTrue(hashes.changed(pages, Set.of("shop.yml")).isEmpty());
    }

    @Test
    void editedContentIsAChange() throws IOException {
        PageHashes hashes = seed();
        write("shop.yml", "id: shop\ntitle: new\n");

        assertEquals(Set.of("shop.yml"), hashes.changed(pages, Set.of("shop.yml")));
    }

    @Test
    void onlyTheEditedFileIsReported() throws IOException {
        write("shop.yml", "id: shop\n");
        write("bank.yml", "id: bank\n");
        PageHashes hashes = new PageHashes();
        hashes.changed(pages, Set.of("shop.yml", "bank.yml"));

        write("bank.yml", "id: bank\ntitle: x\n");

        assertEquals(Set.of("bank.yml"),
                hashes.changed(pages, Set.of("shop.yml", "bank.yml")));
    }

    @Test
    void aDeletedFileIsAChangeAndCanBeRecreated() throws IOException {
        PageHashes hashes = seed();
        Files.delete(pages.resolve("shop.yml"));

        assertEquals(Set.of("shop.yml"), hashes.changed(pages, Set.of("shop.yml")));

        write("shop.yml", "id: shop\n");
        assertEquals(Set.of("shop.yml"), hashes.changed(pages, Set.of("shop.yml")),
                "recreating a file with the old bytes is still a change");
        assertTrue(hashes.changed(pages, Set.of("shop.yml")).isEmpty());
    }

    @Test
    void pathsOutsideTheRootAreIgnored() throws IOException {
        PageHashes hashes = seed();
        assertTrue(hashes.changed(pages, Set.of("../secret.yml")).isEmpty());
    }

    @Test
    void pruneDropsDeletedFiles() throws IOException {
        PageHashes hashes = seed();
        write("gone.yml", "id: gone\n");
        hashes.changed(pages, Set.of("gone.yml"));
        assertEquals(2, hashes.size());

        Files.delete(pages.resolve("gone.yml"));
        hashes.prune(pages);

        assertEquals(1, hashes.size());
        assertFalse(hashes.changed(pages, Set.of("shop.yml")).contains("shop.yml"));
    }

    private void write(String name, String content) throws IOException {
        Path file = pages.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
