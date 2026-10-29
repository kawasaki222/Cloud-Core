package com.cloudcore.server.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.event.ActionResultEvent;
import com.cloudcore.api.event.SessionOpenEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AddonEventBusTest {

    @Test
    void deliversOnlyMatchingTypes() {
        AddonEventBus bus = new AddonEventBus();
        List<SessionOpenEvent> opened = new ArrayList<>();
        List<ActionResultEvent> results = new ArrayList<>();
        bus.add(SessionOpenEvent.class, opened::add);
        bus.add(ActionResultEvent.class, results::add);

        bus.publish(openEvent());

        assertEquals(1, opened.size());
        assertTrue(results.isEmpty());
    }

    @Test
    void listenerFailureDoesNotStopOtherListeners() {
        List<Throwable> errors = new ArrayList<>();
        AddonEventBus bus = new AddonEventBus((type, error) -> errors.add(error));
        List<SessionOpenEvent> seen = new ArrayList<>();
        bus.add(SessionOpenEvent.class, event -> {
            throw new IllegalStateException("boom");
        });
        bus.add(SessionOpenEvent.class, seen::add);

        bus.publish(openEvent());

        assertEquals(1, errors.size());
        assertEquals(1, seen.size(), "a broken addon must not silence the rest");
    }

    @Test
    void removeUnsubscribes() {
        AddonEventBus bus = new AddonEventBus();
        List<SessionOpenEvent> seen = new ArrayList<>();
        java.util.function.Consumer<SessionOpenEvent> listener = seen::add;
        bus.add(SessionOpenEvent.class, listener);

        assertTrue(bus.remove(SessionOpenEvent.class, listener));
        bus.publish(openEvent());

        assertFalse(bus.remove(SessionOpenEvent.class, listener));
        assertTrue(seen.isEmpty());
    }

    private static SessionOpenEvent openEvent() {
        return new SessionOpenEvent(UUID.randomUUID(), "shop", SurfaceType.CUSTOM_SCREEN,
                "shop", 1);
    }
}
