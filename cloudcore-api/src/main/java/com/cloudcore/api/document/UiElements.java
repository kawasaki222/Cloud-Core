package com.cloudcore.api.document;

import java.util.Objects;

/**
 * Element lookup by document-wide id. Ids are unique after {@code repeat} expansion, so a
 * lookup always has one answer; both the server and the client use it for scroll state,
 * hover routing and diagnostics.
 */
public final class UiElements {
    private UiElements() {
    }

    /** @return the element with {@code id}, or {@code null} when the document has none */
    public static UiElement findById(UiDocument document, String id) {
        Objects.requireNonNull(document, "document");
        if (id == null) {
            return null;
        }
        for (UiElement element : document.elements()) {
            UiElement hit = findById(element, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** @return the element with {@code id} at or below {@code element}, or {@code null} */
    public static UiElement findById(UiElement element, String id) {
        Objects.requireNonNull(element, "element");
        if (id == null) {
            return null;
        }
        if (id.equals(element.id())) {
            return element;
        }
        for (UiElement child : element.children()) {
            UiElement hit = findById(child, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }
}
