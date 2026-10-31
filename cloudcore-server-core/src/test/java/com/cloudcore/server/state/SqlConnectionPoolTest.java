package com.cloudcore.server.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The SQL store reuses connections instead of opening one per operation. */
class SqlConnectionPoolTest {
    @TempDir
    Path dir;

    private final AtomicInteger opened = new AtomicInteger();
    private final List<SqlPlayerStateStore> stores = new ArrayList<>();

    @BeforeAll
    static void loadDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
    }

    @AfterEach
    void closeStores() {
        for (SqlPlayerStateStore store : stores) {
            store.close();
        }
        stores.clear();
    }

    private SqlPlayerStateStore store() throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("pool.db");
        SqlPlayerStateStore store = new SqlPlayerStateStore(SqlPlayerStateStore.Dialect.SQLITE,
                () -> {
                    opened.incrementAndGet();
                    return DriverManager.getConnection(url);
                });
        stores.add(store);
        return store;
    }

    @Test
    void repeatedOperationsReuseOneConnection() throws Exception {
        SqlPlayerStateStore store = store();
        store.initialize();
        UUID player = UUID.randomUUID();

        for (int index = 0; index < 20; index++) {
            store.save(player, Map.of("page", Map.of("value", (double) index)));
            store.load(player);
        }

        // One connection for initialize, one for the first save, and it is reused afterwards.
        assertTrue(opened.get() <= 2, "opened " + opened.get() + " connections");
    }

    @Test
    void closingAHandleReturnsItInsteadOfClosingTheConnection() throws Exception {
        SqlPlayerStateStore store = store();
        store.initialize();
        UUID player = UUID.randomUUID();
        store.save(player, Map.of("page", Map.of("value", 1.0)));
        store.load(player);
        store.save(player, Map.of("page", Map.of("value", 2.0)));

        assertEquals(2.0, store.load(player).get("page").get("value"));
    }

    @Test
    void closeDropsTheIdleConnections() throws Exception {
        SqlPlayerStateStore store = store();
        store.initialize();
        UUID player = UUID.randomUUID();
        store.save(player, Map.of("page", Map.of("value", 1.0)));
        store.close();

        // A closed store still works: the next operation opens a fresh connection.
        store.save(player, Map.of("page", Map.of("value", 3.0)));
        assertEquals(3.0, store.load(player).get("page").get("value"));
    }
}
