package com.cloudcore.server.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlPlayerStateStoreTest {
    @TempDir
    Path dir;

    @BeforeAll
    static void loadDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
    }

    private SqlPlayerStateStore store(String file) {
        String url = "jdbc:sqlite:" + dir.resolve(file);
        return new SqlPlayerStateStore(SqlPlayerStateStore.Dialect.SQLITE,
                () -> DriverManager.getConnection(url));
    }

    @Test
    void savesAndLoadsTheSameShapeAsTheFileStore() throws Exception {
        SqlPlayerStateStore store = store("state.db");
        store.initialize();
        UUID player = UUID.randomUUID();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("balance", 12.5);
        values.put("muted", true);
        values.put("name", "Cloud");
        Map<String, Map<String, Object>> state = Map.of("shop", values);

        store.save(player, state);
        Map<String, Map<String, Object>> loaded = store.load(player);

        assertEquals(12.5, loaded.get("shop").get("balance"));
        assertEquals(true, loaded.get("shop").get("muted"));
        assertEquals("Cloud", loaded.get("shop").get("name"));
    }

    @Test
    void upsertReplacesThePreviousRow() throws Exception {
        SqlPlayerStateStore store = store("upsert.db");
        store.initialize();
        UUID player = UUID.randomUUID();
        store.save(player, Map.of("page", Map.of("value", 1.0)));
        store.save(player, Map.of("page", Map.of("value", 2.0)));

        assertEquals(2.0, store.load(player).get("page").get("value"));
    }

    @Test
    void initializeIsIdempotentAndRecordsTheSchemaVersion() throws Exception {
        Path file = dir.resolve("schema.db");
        SqlPlayerStateStore store = store("schema.db");
        store.initialize();
        store.initialize();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT version FROM cloudcore_schema WHERE id = 1")) {
            assertTrue(result.next());
            assertEquals(SqlPlayerStateStore.SCHEMA_VERSION, result.getInt(1));
        }
    }

    @Test
    void missingPlayerIsAnEmptyMap() throws Exception {
        SqlPlayerStateStore store = store("empty.db");
        store.initialize();
        assertTrue(store.load(UUID.randomUUID()).isEmpty());
    }

    @Test
    void corruptPayloadIsReportedNotGuessed() throws Exception {
        Path file = dir.resolve("bad.db");
        SqlPlayerStateStore store = store("bad.db");
        store.initialize();
        UUID player = UUID.randomUUID();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.prepareStatement(
                        "INSERT INTO cloudcore_player_state VALUES (?, ?, ?)")) {
            statement.setString(1, player.toString());
            statement.setString(2, "{not json");
            statement.setLong(3, 0L);
            statement.executeUpdate();
        }

        assertThrows(java.io.IOException.class, () -> store.load(player));
    }
}
