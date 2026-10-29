package com.cloudcore.server.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Typed, synchronous dispatcher for addon listeners. Pure Java so it can be tested without a
 * server; the plugin supplies a logger as the error handler.
 *
 * <p>A listener exception is reported once and does not stop the remaining listeners or the
 * caller, matching the promise in {@code docs/19-addon-api.md}.
 */
public final class AddonEventBus {

    private final Map<Class<?>, CopyOnWriteArrayList<Consumer<?>>> listeners =
            new LinkedHashMap<>();
    private final BiConsumer<Class<?>, Throwable> errorHandler;

    public AddonEventBus() {
        this((type, error) -> {
        });
    }

    public AddonEventBus(BiConsumer<Class<?>, Throwable> errorHandler) {
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    }

    public <T> void add(Class<T> type, Consumer<? super T> listener) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(listener, "listener");
        CopyOnWriteArrayList<Consumer<?>> bucket = listeners.computeIfAbsent(type,
                ignored -> new CopyOnWriteArrayList<>());
        if (!bucket.contains(listener)) {
            bucket.add(listener);
        }
    }

    /** @return true when the listener was registered */
    public <T> boolean remove(Class<T> type, Consumer<? super T> listener) {
        if (type == null) {
            return false;
        }
        CopyOnWriteArrayList<Consumer<?>> bucket = listeners.get(type);
        return bucket != null && bucket.remove(listener);
    }

    /** Delivers {@code event} to every listener whose registered type matches it. */
    public <T> void publish(T event) {
        Objects.requireNonNull(event, "event");
        for (Map.Entry<Class<?>, CopyOnWriteArrayList<Consumer<?>>> entry
                : listeners.entrySet()) {
            if (!entry.getKey().isInstance(event)) {
                continue;
            }
            for (Consumer<?> listener : entry.getValue()) {
                try {
                    deliver(listener, event);
                } catch (RuntimeException exception) {
                    errorHandler.accept(entry.getKey(), exception);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void deliver(Consumer<?> listener, T event) {
        ((Consumer<? super T>) listener).accept(event);
    }

    public void clear() {
        listeners.clear();
    }
}
