package com.cloudcore.server.state;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Persists per-player page state so a balance, a toggle or a scroll offset survives a relog.
 *
 * <p>The shape is page name → state key → value, with values limited to what the document
 * carries today (numbers, booleans, strings). Implementations must be safe to call off the
 * main thread: the server saves on a worker and never blocks a tick on disk.
 *
 * <p>The default implementation is a JSON file per player ({@link FilePlayerStateStore});
 * SQLite and MySQL implementations fit behind this interface.
 */
public interface PlayerStateStore {

    /** @return the stored state, or an empty map when nothing was saved yet */
    Map<String, Map<String, Object>> load(UUID player) throws IOException;

    /** Replaces the player's stored state. Called from a worker thread. */
    void save(UUID player, Map<String, Map<String, Object>> state) throws IOException;

    /** @return every player with stored state, for export/migration tools */
    java.util.Set<UUID> players() throws IOException;
}
