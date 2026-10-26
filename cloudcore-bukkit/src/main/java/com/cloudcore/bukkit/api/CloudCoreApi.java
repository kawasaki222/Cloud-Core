package com.cloudcore.bukkit.api;

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

    /** @return true when the player's client completed the Cloud Core handshake */
    boolean isModded(Player player);

    /** Validates an action name the same way {@link #registerActionHandler} does. */
    static boolean isValidActionName(String name) {
        return com.cloudcore.server.action.ActionNames.isValid(name);
    }
}
