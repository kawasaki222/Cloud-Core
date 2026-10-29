package com.cloudcore.bukkit.api;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.surface.SurfaceProvider;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/**
 * The stable surface a server plugin or addon uses to talk to Cloud Core.
 *
 * <p>Obtain it once from the services manager:
 * <pre>{@code
 * CloudCoreApi api = Bukkit.getServicesManager().load(CloudCoreApi.class);
 * }</pre>
 * The instance survives plugin reloads of Cloud Core; holding it for the lifetime of the
 * addon is safe. Every method except {@link #isModded} must be called on the main thread.
 *
 * <p>This type is a 1.x contract: methods may be added with default implementations, but
 * existing signatures keep their meaning.
 */
public interface CloudCoreApi {

    /**
     * Opens a loaded YAML page for a player as a custom screen.
     *
     * @return the outcome; see {@link OpenResult}
     */
    OpenResult open(Player player, String pageId);

    /** Opens a page with explicit options. */
    OpenResult open(Player player, String pageId, OpenOptions options);

    /**
     * Opens a page built in code with {@link com.cloudcore.api.document.PageBuilder}. The
     * document, its action table and its state go through the same send path as a YAML page;
     * because there is no file to recompile, {@code set_state} cannot refresh such a page —
     * rebuild and open it again instead.
     */
    OpenResult open(Player player, com.cloudcore.api.page.PageLoader.CompiledPage page);

    /** Opens a code-built page with explicit options. */
    OpenResult open(Player player, com.cloudcore.api.page.PageLoader.CompiledPage page,
            OpenOptions options);

    /**
     * Registers a server-side action under {@code name}. Pages refer to it as
     * {@code action: <name>} and may pass arbitrary parameters, which arrive in {@code args}.
     * Names are case-insensitive and must match {@code [a-z0-9_.-]{1,64}}.
     *
     * @throws IllegalArgumentException when the name is invalid or already registered
     */
    void registerActionHandler(String name, ActionHandler handler);

    /**
     * Removes a previously registered action.
     *
     * @return true when a handler was removed
     */
    boolean unregisterActionHandler(String name);

    /**
     * Resolves {@code %namespace_params%} tokens in page text. The namespace is
     * {@code [a-z0-9_]{1,16}} and case-insensitive; custom resolvers run before
     * PlaceholderAPI, so a namespace may deliberately shadow an expansion.
     *
     * @throws IllegalArgumentException for an invalid or already registered namespace
     */
    void registerPlaceholderResolver(String namespace, PlaceholderResolver resolver);

    /** @return true when a resolver was removed */
    boolean unregisterPlaceholderResolver(String namespace);

    /**
     * Serves {@link #open(Player, String)} for a page id with a page built on demand. The
     * provider wins over a YAML file with the same id.
     *
     * @throws IllegalArgumentException for an invalid or already registered page id
     */
    void registerPageProvider(String pageId, PageProvider provider);

    /** @return true when a provider was removed */
    boolean unregisterPageProvider(String pageId);

    /**
     * Participates in a surface's lifecycle: {@code open} before the document is sent,
     * {@code apply} after it is queued, {@code close} when the UI closes. The core still sends
     * the document and owns the layout; the provider may bind external state (an inventory, a
     * boss bar) and supplies the correlation token. One provider per surface.
     *
     * <p>The client mod must already render the surface type, so this cannot introduce a new
     * surface on its own. {@link SurfaceType#UNKNOWN} is rejected.
     *
     * @throws IllegalArgumentException for {@code UNKNOWN} or an already registered surface
     */
    void registerSurfaceProvider(SurfaceProvider provider);

    /** @return true when a provider was removed */
    boolean unregisterSurfaceProvider(SurfaceType surface);

    /**
     * Subscribes to a Cloud Core event type ({@code SessionOpenEvent},
     * {@code SessionCloseEvent}, {@code ElementEvent}, {@code ActionResultEvent}).
     *
     * <p>Listeners run synchronously on the main thread. Exceptions are caught and logged and
     * never interrupt the UI or the other listeners. Subscribe/unsubscribe on the main thread.
     */
    <T> void addEventListener(Class<T> type, Consumer<? super T> listener);

    /** @return true when the listener was registered */
    <T> boolean removeEventListener(Class<T> type, Consumer<? super T> listener);

    /** @return true when the player's client completed the Cloud Core handshake */
    boolean isModded(Player player);

    /** Validates an action name the same way {@link #registerActionHandler} does. */
    static boolean isValidActionName(String name) {
        return com.cloudcore.server.action.ActionNames.isValid(name);
    }
}
