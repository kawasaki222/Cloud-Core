package com.cloudcore.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Collects events from an addon event listener: {@code bus.add(SessionOpenEvent.class,
 * capture)}.
 *
 * <p>Not thread-safe by design; events are delivered on the main thread.
 */
public final class EventCapture<T> implements Consumer<T> {

    private final List<T> events = new ArrayList<>();

    @Override
    public void accept(T event) {
        events.add(event);
    }

    public List<T> events() {
        return List.copyOf(events);
    }

    public int size() {
        return events.size();
    }

    /** @throws AssertionError when nothing was captured */
    public T last() {
        if (events.isEmpty()) {
            throw new AssertionError("No event captured");
        }
        return events.get(events.size() - 1);
    }

    public void clear() {
        events.clear();
    }
}
