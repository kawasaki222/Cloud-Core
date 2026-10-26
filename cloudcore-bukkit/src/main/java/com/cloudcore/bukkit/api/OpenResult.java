package com.cloudcore.bukkit.api;

/**
 * Outcome of {@link CloudCoreApi#open}. Failures are reported here instead of throwing so an
 * addon can branch on them without catching exceptions for ordinary conditions.
 */
public enum OpenResult {
    /** UI_OPEN and the document were sent; the client answers with UI_READY. */
    OPENED,
    /** Called off the main thread; the open was scheduled for the next tick. */
    SCHEDULED,
    /** The player's client has no Cloud Core mod, so nothing was sent. */
    NOT_MODDED,
    /** No page with that id is loaded. */
    UNKNOWN_PAGE,
    /** The page or the requested options use a surface this call does not open (v1: screens). */
    UNSUPPORTED_SURFACE,
    /** The page failed to compile; the reason is in the server log. */
    FAILED
}
