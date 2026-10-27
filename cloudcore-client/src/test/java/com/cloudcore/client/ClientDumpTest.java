package com.cloudcore.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientDumpTest {

    @TempDir
    Path root;

    @Test
    void writesRedactedEntries() throws Exception {
        Path logs = root.resolve("logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("cloudcore-client.log"),
                "connect 192.168.1.50:25565\n"
                        + "path C:\\Users\\alice\\AppData\\Roaming\n"
                        + "Authorization: Bearer abcdef0123456789\n");
        Path config = root.resolve("config").resolve("cloudcore-client.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"renderer\":\"nanovg\"}");

        Path zip = ClientDump.write(logs, config,
                List.of("10:00:00.000 S2C op=0x03 len=42"),
                Map.of("minecraft", "1.21.11"), 1_700_000_000_000L);

        assertTrue(Files.isRegularFile(zip), zip.toString());
        Map<String, String> entries = read(zip);
        assertEquals(Set.of("system.txt", "cloudcore-client.log", "config.json", "frames.txt"),
                entries.keySet());

        String log = entries.get("cloudcore-client.log");
        assertFalse(log.contains("192.168.1.50"), log);
        assertFalse(log.contains("alice"), log);
        assertFalse(log.contains("abcdef0123456789"), log);
        assertTrue(log.contains("<ip>"), log);
        assertTrue(entries.get("frames.txt").contains("op=0x03"));
        assertTrue(entries.get("system.txt").contains("minecraft=1.21.11"));
        // A Java version has three dots and must not be mistaken for an address.
        assertTrue(entries.get("system.txt").contains(
                "java.version=" + System.getProperty("java.version")),
                entries.get("system.txt"));
    }

    @Test
    void leavesVersionsAndIdentifiersAlone() {
        String text = ClientDump.redact("version 1.21.11 ui=rpg_hud element=slider_demo");

        assertTrue(text.contains("1.21.11"), text);
        assertTrue(text.contains("rpg_hud"), text);
    }

    private static Map<String, String> read(Path zip) throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (InputStream raw = Files.newInputStream(zip);
                ZipInputStream in = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
