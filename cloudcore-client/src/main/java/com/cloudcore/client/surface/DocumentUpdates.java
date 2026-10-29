package com.cloudcore.client.surface;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import com.cloudcore.protocol.ElementUpdate;
import com.cloudcore.protocol.WireValue;
import com.cloudcore.protocol.WireValues;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Applies an {@code ELEMENT_UPDATE} to the client's current document. Untouched elements and
 * attribute maps are shared with the previous revision, so an update costs only the changed
 * branch.
 *
 * <p>An unknown element or property id rejects the whole update: applying half of it would
 * leave the surface in a state the server never authored.
 */
public final class DocumentUpdates {

    /**
     * @param applied false when the update could not be applied; {@code document} is then the
     *                original instance and {@code problem} says why
     */
    public record Result(UiDocument document, boolean applied, String problem) {
        static Result failure(UiDocument document, String problem) {
            return new Result(document, false, problem);
        }
    }

    private DocumentUpdates() {
    }

    public static Result apply(UiDocument document, List<ElementUpdate.Entry> entries) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            return new Result(document, true, "");
        }
        Map<String, List<ElementUpdate.Entry>> byElement = new LinkedHashMap<>();
        for (ElementUpdate.Entry entry : entries) {
            byElement.computeIfAbsent(entry.elementId(), ignored -> new ArrayList<>())
                    .add(entry);
        }
        Set<String> remaining = new HashSet<>(byElement.keySet());
        List<UiElement> rewritten = rewrite(document.elements(), byElement, remaining);
        if (rewritten == null) {
            return Result.failure(document, "unknown element or property");
        }
        if (!remaining.isEmpty()) {
            return Result.failure(document, "unknown element " + remaining.iterator().next());
        }
        return new Result(new UiDocument(document.schema(), document.id(), document.surface(),
                document.title(), document.theme(), document.width(), document.height(),
                document.scale(), document.container(), document.surfaceOptions(),
                document.fonts(), rewritten), true, "");
    }

    /** @return rewritten elements, or {@code null} when an entry cannot be applied */
    private static List<UiElement> rewrite(List<UiElement> elements,
            Map<String, List<ElementUpdate.Entry>> byElement, Set<String> remaining) {
        List<UiElement> rewritten = null;
        for (int index = 0; index < elements.size(); index++) {
            UiElement original = elements.get(index);
            UiElement updated = rewrite(original, byElement, remaining);
            if (updated == null) {
                return null;
            }
            if (updated != original) {
                if (rewritten == null) {
                    rewritten = new ArrayList<>(elements);
                }
                rewritten.set(index, updated);
            }
        }
        return rewritten != null ? rewritten : elements;
    }

    private static UiElement rewrite(UiElement element,
            Map<String, List<ElementUpdate.Entry>> byElement, Set<String> remaining) {
        List<UiElement> children = rewrite(element.children(), byElement, remaining);
        if (children == null) {
            return null;
        }
        List<ElementUpdate.Entry> own = byElement.get(element.id());
        if (own == null && children == element.children()) {
            return element;
        }
        remaining.remove(element.id());

        boolean visible = element.visible();
        boolean enabled = element.enabled();
        Map<String, Object> attributes = element.attributes();
        boolean attributesCopied = false;
        if (own != null) {
            attributes = new LinkedHashMap<>(attributes);
            attributesCopied = true;
            for (ElementUpdate.Entry entry : own) {
                switch (entry.propertyId()) {
                    case ElementUpdate.PROPERTY_VISIBLE -> {
                        if (!(entry.value() instanceof WireValue.BoolValue flag)) {
                            return null;
                        }
                        visible = flag.value();
                    }
                    case ElementUpdate.PROPERTY_ENABLED -> {
                        if (!(entry.value() instanceof WireValue.BoolValue flag)) {
                            return null;
                        }
                        enabled = flag.value();
                    }
                    case ElementUpdate.PROPERTY_TEXT -> applyText(attributes, entry.value());
                    case ElementUpdate.PROPERTY_TEXTURE -> applyAttribute(attributes,
                            "texture", entry.value());
                    case ElementUpdate.PROPERTY_ITEM -> applyAttribute(attributes,
                            "item", entry.value());
                    case ElementUpdate.PROPERTY_PROGRESS -> applyAttribute(attributes,
                            "value", entry.value());
                    case ElementUpdate.PROPERTY_VALUE -> applyAttribute(attributes,
                            "value", entry.value());
                    case ElementUpdate.PROPERTY_TOOLTIP -> applyAttribute(attributes,
                            "tooltip", entry.value());
                    case ElementUpdate.PROPERTY_STYLE -> applyAttribute(attributes,
                            "style", entry.value());
                    case ElementUpdate.PROPERTY_SELECTED -> applyAttribute(attributes,
                            "selected", entry.value());
                    case ElementUpdate.PROPERTY_INVALID -> applyAttribute(attributes,
                            "invalid", entry.value());
                    case ElementUpdate.PROPERTY_CHECKED -> applyAttribute(attributes,
                            "checked", entry.value());
                    case ElementUpdate.PROPERTY_SCROLL_Y -> applyAttribute(attributes,
                            "scroll_y", entry.value());
                    case ElementUpdate.PROPERTY_COLOR -> applyAttribute(attributes,
                            "color", entry.value());
                    case ElementUpdate.PROPERTY_PLACEHOLDER -> applyAttribute(attributes,
                            "placeholder", entry.value());
                    default -> {
                        return null;
                    }
                }
            }
        }
        if (!attributesCopied && children == element.children()) {
            return element;
        }
        return new UiElement(element.id(), element.type(), element.x(), element.y(),
                element.width(), element.height(), visible, enabled, element.z(),
                element.events(), children, attributes);
    }

    /** Property 1 carries plain text as a string and rich text as a list of run maps. */
    private static void applyText(Map<String, Object> attributes, WireValue value) {
        if (value instanceof WireValue.NullValue) {
            attributes.remove("text");
            attributes.remove("runs");
            return;
        }
        if (value instanceof WireValue.StringValue text) {
            attributes.put("text", text.value());
            attributes.remove("runs");
            return;
        }
        attributes.put("runs", WireValues.fromWire(value));
        attributes.remove("text");
    }

    private static void applyAttribute(Map<String, Object> attributes, String key,
            WireValue value) {
        if (value instanceof WireValue.NullValue) {
            attributes.remove(key);
        } else {
            attributes.put(key, WireValues.fromWire(value));
        }
    }
}
