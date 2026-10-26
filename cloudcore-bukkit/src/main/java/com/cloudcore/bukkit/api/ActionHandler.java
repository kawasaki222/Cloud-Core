package com.cloudcore.bukkit.api;

import java.util.Map;

/**
 * A server-side action an addon registers under a name. Pages invoke it as
 * {@code action: <name>}; parameters from the YAML arrive in {@code args}.
 *
 * <p>Runs on the main thread and must return quickly; schedule long work yourself. An
 * exception is logged with the page, element and action id, and the click reports
 * {@code REJECTED} to the client.
 */
@FunctionalInterface
public interface ActionHandler {

    void handle(ActionContext context, Map<String, Object> args) throws Exception;

    /**
     * Permission node required before the action runs; {@code null} means no check.
     * A denied check rejects the rest of the click.
     */
    default String permission() {
        return null;
    }
}
