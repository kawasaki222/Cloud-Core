package com.cloudcore.server.state;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * JDBC-backed page state for servers that outgrow one JSON file per player. The table stores
 * the same {@link StateJson} document as the file store, so moving between backends is a
 * copy, not a schema translation.
 *
 * <p>SQLite is bundled with the plugin; MySQL is supported when the server already provides a
 * JDBC driver and URL. Calls are synchronized per store: the plugin has a single persistence
 * worker, and SQLite's file lock is happier with one writer.
 */
public final class SqlPlayerStateStore implements PlayerStateStore {
    public enum Dialect {
        SQLITE,
        MYSQL
    }

    /** Opens a connection for one operation. The caller closes it. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private static final String TABLE = "cloudcore_player_state";
    private static final String SCHEMA_TABLE = "cloudcore_schema";
    public static final int SCHEMA_VERSION = 1;

    private final Dialect dialect;
    private final Connections connections;

    public SqlPlayerStateStore(Dialect dialect, Connections connections) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** Creates the table if it is missing. Safe to call on every startup. */
    public void initialize() throws IOException {
        String type = dialect == Dialect.MYSQL ? "LONGTEXT" : "TEXT";
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "player_id VARCHAR(36) NOT NULL PRIMARY KEY,"
                + "payload " + type + " NOT NULL,"
                + "updated_at BIGINT NOT NULL)";
        String schemaSql = "CREATE TABLE IF NOT EXISTS " + SCHEMA_TABLE
                + " (id INTEGER NOT NULL PRIMARY KEY, version INTEGER NOT NULL)";
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute(schemaSql);
            statement.execute(sql);
            if (dialect == Dialect.SQLITE) {
                try (var pragma = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                    pragma.next();
                }
            }
            int version = -1;
            try (var query = connection.prepareStatement(
                    "SELECT version FROM " + SCHEMA_TABLE + " WHERE id = 1");
                    var result = query.executeQuery()) {
                if (result.next()) {
                    version = result.getInt(1);
                }
            }
            if (version > SCHEMA_VERSION) {
                throw new IOException("state schema version " + version
                        + " is newer than this build understands (" + SCHEMA_VERSION + ")");
            }
            if (version < 0) {
                try (var insert = connection.prepareStatement(
                        "INSERT INTO " + SCHEMA_TABLE + " (id, version) VALUES (1, ?)")) {
                    insert.setInt(1, SCHEMA_VERSION);
                    insert.executeUpdate();
                }
            } else if (version < SCHEMA_VERSION) {
                try (var update = connection.prepareStatement(
                        "UPDATE " + SCHEMA_TABLE + " SET version = ? WHERE id = 1")) {
                    update.setInt(1, SCHEMA_VERSION);
                    update.executeUpdate();
                }
            }
        } catch (SQLException exception) {
            throw new IOException("cannot create the page state tables", exception);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = connections.open();
        if (dialect == Dialect.SQLITE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
            }
        }
        return connection;
    }

    @Override
    public synchronized Map<String, Map<String, Object>> load(UUID player) throws IOException {
        Objects.requireNonNull(player, "player");
        String sql = "SELECT payload FROM " + TABLE + " WHERE player_id = ?";
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Map.of();
                }
                return StateJson.decode(result.getString(1), player.toString());
            }
        } catch (SQLException exception) {
            throw new IOException("cannot load page state for " + player, exception);
        }
    }

    @Override
    public synchronized void save(UUID player, Map<String, Map<String, Object>> state)
            throws IOException {
        Objects.requireNonNull(player, "player");
        String json = StateJson.encode(state);
        String sql = dialect == Dialect.MYSQL
                ? "INSERT INTO " + TABLE + " (player_id, payload, updated_at)"
                        + " VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE"
                        + " payload = VALUES(payload), updated_at = VALUES(updated_at)"
                : "INSERT INTO " + TABLE + " (player_id, payload, updated_at)"
                        + " VALUES (?, ?, ?) ON CONFLICT(player_id) DO UPDATE SET"
                        + " payload = excluded.payload, updated_at = excluded.updated_at";
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            statement.setString(2, json);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IOException("cannot save page state for " + player, exception);
        }
    }
}
