package com.cloudcore.testkit;

import com.cloudcore.api.CloudCoreApi;
import com.cloudcore.api.OpenOptions;
import com.cloudcore.api.OpenResult;
import com.cloudcore.api.SurfaceType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link CloudCoreApi} for addon tests: no Paper, no server scheduler, deterministic.
 *
 * <p>It records what an addon asked for rather than rendering anything. State is keyed by
 * player and uiId, matching the server's session scope.
 */
public final class FakeCloudCoreApi implements CloudCoreApi {

    /** One recorded {@link #open} call. */
    public record OpenCall(UUID playerId, String pageId, OpenOptions options) {
    }

    private final Map<UUID, EnumMap<SurfaceType, String>> active = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> state = new ConcurrentHashMap<>();
    private final List<OpenCall> opens = new CopyOnWriteArrayList<>();
    private final AtomicInteger reloads = new AtomicInteger();

    @Override
    public OpenResult open(UUID playerId, String pageId, OpenOptions options) {
        if (playerId == null || pageId == null || options == null) {
            return OpenResult.FAILED;
        }
        opens.add(new OpenCall(playerId, pageId, options));
        active.computeIfAbsent(playerId, ignored -> new EnumMap<>(SurfaceType.class))
                .put(options.surface(), pageId);
        return OpenResult.OPENED;
    }

    @Override
    public boolean close(UUID playerId, SurfaceType surface) {
        EnumMap<SurfaceType, String> perPlayer = playerId == null ? null : active.get(playerId);
        return perPlayer != null && perPlayer.remove(surface) != null;
    }

    @Override
    public Optional<String> activeDocument(UUID playerId, SurfaceType surface) {
        EnumMap<SurfaceType, String> perPlayer = playerId == null ? null : active.get(playerId);
        return perPlayer == null ? Optional.empty()
                : Optional.ofNullable(perPlayer.get(surface));
    }

    @Override
    public void setState(UUID playerId, String uiId, String key, Object value) {
        if (playerId == null || uiId == null || key == null) {
            return;
        }
        state.computeIfAbsent(scope(playerId, uiId), ignored -> new ConcurrentHashMap<>())
                .put(key, value);
    }

    @Override
    public Optional<Object> getState(UUID playerId, String uiId, String key) {
        if (playerId == null || uiId == null || key == null) {
            return Optional.empty();
        }
        Map<String, Object> values = state.get(scope(playerId, uiId));
        return values == null ? Optional.empty() : Optional.ofNullable(values.get(key));
    }

    @Override
    public void reload() {
        reloads.incrementAndGet();
    }

    /** Every {@link #open} call in order. */
    public List<OpenCall> opens() {
        return new ArrayList<>(opens);
    }

    public int reloadCount() {
        return reloads.get();
    }

    public void clear() {
        active.clear();
        state.clear();
        opens.clear();
        reloads.set(0);
    }

    private static String scope(UUID playerId, String uiId) {
        return playerId + "/" + uiId;
    }
}
