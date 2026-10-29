package com.cloudcore.api.surface;

import com.cloudcore.api.OpenOptions;
import com.cloudcore.api.SurfaceType;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side surface implementation, callable by addons through
 * {@code CloudCoreApi#registerSurfaceProvider}.
 *
 * <p>Cloud Core still sends {@code UI_OPEN}/{@code UI_DEFINITION} and owns the document; a
 * provider participates in the lifecycle so an addon can bind its own backing state (an
 * inventory, a boss bar, an external session) and supply the correlation token:
 *
 * <ol>
 *   <li>{@link #open} runs before the document is sent. Returning empty declines the open.</li>
 *   <li>{@link #apply} runs after the definition is queued, e.g. to push persistent config.</li>
 *   <li>{@link #close} runs when the server closes the UI or the client reports
 *       {@code UI_CLOSED}; it must be idempotent.</li>
 * </ol>
 *
 * <p>The client mod must already understand the {@link SurfaceType}; a provider cannot invent
 * a surface the client cannot render. All methods are called on the server main thread.
 */
public interface SurfaceProvider {

    SurfaceType type();

    /** @return true when this provider can serve its surface right now */
    boolean available();

    /**
     * Prepares the surface for a document.
     *
     * @return the session, or empty to decline (Cloud Core logs it and sends nothing)
     */
    Optional<SurfaceSession> open(UUID playerId, String documentId, OpenOptions options);

    /** Called after the document was queued; persistent surfaces push their config here. */
    default void apply(SurfaceSession session) {
    }

    /** Tears the surface down. Must tolerate being called for an unknown session. */
    void close(UUID playerId, String uiId);
}
