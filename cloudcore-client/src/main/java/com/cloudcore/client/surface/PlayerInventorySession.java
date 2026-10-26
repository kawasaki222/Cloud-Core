package com.cloudcore.client.surface;

import com.cloudcore.api.document.UiDocument;
import java.util.Objects;

/**
 * Holds the player-inventory skin binding: which document is active and whether it survives
 * repeated inventory opens. Kept free of Minecraft types so the binding rules are unit-tested.
 *
 * <p>Thread: client tick / render thread only (single writer).
 */
final class PlayerInventorySession {
    private UiDocument document;
    private boolean active;
    private boolean persistent;

    void open(UiDocument document, boolean persistent) {
        this.document = Objects.requireNonNull(document, "document");
        this.persistent = persistent;
        this.active = true;
    }

    void close(String uiId) {
        if (document != null && document.id().equals(uiId)) {
            document = null;
            active = false;
            persistent = false;
        }
    }

    /**
     * @return the document a newly opened vanilla inventory should be skinned with, or
     *         {@code null} to leave the vanilla screen alone.
     */
    UiDocument documentForNextOpen() {
        if (!active || document == null) {
            return null;
        }
        if (!persistent) {
            active = false;
        }
        return document;
    }

    boolean active() {
        return active;
    }

    boolean persistent() {
        return persistent;
    }

    String activeUiId() {
        return document == null ? null : document.id();
    }
}
