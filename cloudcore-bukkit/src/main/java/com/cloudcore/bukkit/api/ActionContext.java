package com.cloudcore.bukkit.api;

import java.util.Map;
import org.bukkit.entity.Player;

/**
 * What a custom {@link ActionHandler} sees when a page action fires. The state map is a
 * snapshot for reading; writes go through your own storage, then {@link #refresh()}.
 */
public interface ActionContext {

    Player player();

    /** Document id of the page that fired the action. */
    String uiId();

    /** Element that fired the action, e.g. {@code rows[2].buy}. */
    String elementId();

    /** Page state after this click's earlier actions, merged over the document defaults. */
    Map<String, Object> state();

    /** Sends a chat message to the player (plain text). */
    void message(String text);

    /**
     * Recompiles the page with the current session state and pushes a new revision. Call it
     * after changing state the page reads; calling it without a change is a cheap no-op.
     */
    void refresh();
}
