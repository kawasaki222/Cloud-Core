package com.cloudcore.server.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateTransferTest {
    @TempDir
    Path dir;

    @BeforeAll
    static void loadDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
    }

    @Test
    void exportsFilesAndImportsThemIntoSQLite() throws Exception {
        FilePlayerStateStore files = new FilePlayerStateStore(dir.resolve("files"));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        files.save(first, Map.of("shop", Map.of("balance", 12.5)));
        files.save(second, Map.of("hud", Map.of("muted", true)));

        Path export = dir.resolve("export.json");
        assertEquals(2, StateTransfer.export(files, export));

        SqlPlayerStateStore sqlite = sqlite("target.db");
        sqlite.initialize();
        assertEquals(2, StateTransfer.importFile(sqlite, export));

        assertEquals(12.5, sqlite.load(first).get("shop").get("balance"));
        assertEquals(true, sqlite.load(second).get("hud").get("muted"));
        assertEquals(2, sqlite.players().size());
        assertTrue(files.players().contains(first));
    }

    @Test
    void rejectsANewerExportVersion() throws Exception {
        Path export = dir.resolve("future.json");
        Files.writeString(export, "{\"version\":99,\"players\":{}}");
        FilePlayerStateStore target = new FilePlayerStateStore(dir.resolve("target"));

        assertThrows(java.io.IOException.class,
                () -> StateTransfer.importFile(target, export));
    }

    private SqlPlayerStateStore sqlite(String name) {
        String url = "jdbc:sqlite:" + dir.resolve(name);
        return new SqlPlayerStateStore(SqlPlayerStateStore.Dialect.SQLITE,
                () -> DriverManager.getConnection(url));
    }
}
