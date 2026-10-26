package com.cloudcore.server.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePlayerStateStoreTest {
    private static final UUID PLAYER = UUID.fromString("57e72990-8f4d-370a-9882-2dee063faae3");

    @TempDir
    Path root;

    @Test
    void roundTripsPagesAndValueTypes() throws Exception {
        FilePlayerStateStore store = new FilePlayerStateStore(root);
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("clicks", 3.0);
        example.put("notify", true);
        example.put("hint", "hello");
        store.save(PLAYER, Map.of("example", example));

        Map<String, Map<String, Object>> loaded = store.load(PLAYER);

        assertEquals(1, loaded.size());
        Map<String, Object> page = loaded.get("example");
        assertEquals(3.0, page.get("clicks"));
        assertEquals(true, page.get("notify"));
        assertEquals("hello", page.get("hint"));
    }

    @Test
    void missingFileLoadsEmpty() throws Exception {
        assertTrue(new FilePlayerStateStore(root).load(PLAYER).isEmpty());
    }

    @Test
    void integersAreStoredAsNumbers() throws Exception {
        FilePlayerStateStore store = new FilePlayerStateStore(root);
        store.save(PLAYER, Map.of("p", Map.of("count", 7)));

        assertEquals(7.0, store.load(PLAYER).get("p").get("count"));
    }

    @Test
    void overwriteReplacesThePreviousState() throws Exception {
        FilePlayerStateStore store = new FilePlayerStateStore(root);
        store.save(PLAYER, Map.of("p", Map.of("a", 1.0)));
        store.save(PLAYER, Map.of("q", Map.of("b", 2.0)));

        Map<String, Map<String, Object>> loaded = store.load(PLAYER);
        assertEquals(1, loaded.size());
        assertTrue(loaded.containsKey("q"));
        assertTrue(Files.notExists(root.resolve(PLAYER + ".json.tmp")),
                "the temporary file is moved into place");
    }

    @Test
    void corruptJsonIsRejected() throws Exception {
        Files.writeString(root.resolve(PLAYER + ".json"), "{not json",
                StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> new FilePlayerStateStore(root).load(PLAYER));
    }

    @Test
    void newerVersionsAreRejectedInsteadOfGuessed() throws Exception {
        Files.writeString(root.resolve(PLAYER + ".json"),
                "{\"version\":99,\"pages\":{}}", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> new FilePlayerStateStore(root).load(PLAYER));
    }

    @Test
    void oversizedFilesAreRejectedBeforeReading() throws Exception {
        byte[] filler = new byte[(int) FilePlayerStateStore.MAX_FILE_BYTES + 16];
        java.util.Arrays.fill(filler, (byte) ' ');
        Files.write(root.resolve(PLAYER + ".json"), filler);
        assertThrows(IOException.class, () -> new FilePlayerStateStore(root).load(PLAYER));
    }

    @Test
    void longStringsAreTruncatedOnSave() throws Exception {
        FilePlayerStateStore store = new FilePlayerStateStore(root);
        store.save(PLAYER, Map.of("p", Map.of("text", "x".repeat(5000))));

        String loaded = (String) store.load(PLAYER).get("p").get("text");
        assertEquals(FilePlayerStateStore.MAX_STRING_LENGTH, loaded.length());
    }
}
