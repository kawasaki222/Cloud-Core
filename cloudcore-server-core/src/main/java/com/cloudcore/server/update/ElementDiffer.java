package com.cloudcore.server.update;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import com.cloudcore.protocol.ElementUpdate;
import com.cloudcore.protocol.WireValue;
import com.cloudcore.protocol.WireValues;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compares two compiled revisions of one document and produces an {@code ELEMENT_UPDATE}
 * when the difference is representable as element properties.
 *
 * <p>Anything structural — element order, ids, geometry, events, children, document size,
 * title or theme — falls back to a full {@code UI_DEFINITION}. So does a changed
 * {@code text_field}: patching its attributes would desynchronise the vanilla widget's
 * text from the document, so the full path (which rebuilds the screen) is safer.
 */
public final class ElementDiffer {

    /** Bound from {@code Limits.S2C.maxWireEntries}; the codec enforces it too. */
    private static final int MAX_ENTRIES = 4096;

    public enum Mode {
        /** The documents are identical; send nothing. */
        UNCHANGED,
        /** An {@code ElementUpdate} carries every difference. */
        INCREMENTAL,
        /** The difference is not representable; resend the full document. */
        FULL
    }

    public record Result(Mode mode, List<ElementUpdate.Entry> entries) {
        public Result {
            entries = List.copyOf(entries);
        }

        static Result of(Mode mode) {
            return new Result(mode, List.of());
        }
    }

    private ElementDiffer() {
    }

    public static Result diff(UiDocument previous, UiDocument next) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        if (previous == next) {
            return Result.of(Mode.UNCHANGED);
        }
        if (!sameDocument(previous, next)) {
            return Result.of(Mode.FULL);
        }
        List<ElementUpdate.Entry> entries = new ArrayList<>();
        if (!appendChanges(previous.elements(), next.elements(), entries)) {
            return Result.of(Mode.FULL);
        }
        if (entries.isEmpty()) {
            return Result.of(Mode.UNCHANGED);
        }
        if (entries.size() > MAX_ENTRIES) {
            return Result.of(Mode.FULL);
        }
        return new Result(Mode.INCREMENTAL, entries);
    }

    private static boolean sameDocument(UiDocument previous, UiDocument next) {
        return previous.schema() == next.schema()
                && previous.id().equals(next.id())
                && previous.surface() == next.surface()
                && Objects.equals(previous.title(), next.title())
                && Objects.equals(previous.theme(), next.theme())
                && previous.width() == next.width()
                && previous.height() == next.height()
                && previous.scale() == next.scale()
                && Objects.equals(previous.container(), next.container())
                && Objects.equals(previous.surfaceOptions(), next.surfaceOptions())
                && Objects.equals(previous.fonts(), next.fonts());
    }

    private static boolean appendChanges(List<UiElement> previous, List<UiElement> next,
            List<ElementUpdate.Entry> out) {
        if (previous.size() != next.size()) {
            return false;
        }
        for (int index = 0; index < previous.size(); index++) {
            if (!appendChanges(previous.get(index), next.get(index), out)) {
                return false;
            }
        }
        return true;
    }

    private static boolean appendChanges(UiElement previous, UiElement next,
            List<ElementUpdate.Entry> out) {
        if (!previous.id().equals(next.id()) || !previous.type().equals(next.type())
                || previous.x() != next.x() || previous.y() != next.y()
                || !Objects.equals(previous.width(), next.width())
                || !Objects.equals(previous.height(), next.height())
                || previous.z() != next.z()
                || !Objects.equals(previous.events(), next.events())
                || previous.children().size() != next.children().size()) {
            return false;
        }
        if (previous.visible() != next.visible()) {
            if ("text_field".equals(next.type())
                    || !add(out, previous.id(), ElementUpdate.PROPERTY_VISIBLE,
                            new WireValue.BoolValue(next.visible()))) {
                return false;
            }
        }
        if (previous.enabled() != next.enabled()) {
            if ("text_field".equals(next.type())
                    || !add(out, previous.id(), ElementUpdate.PROPERTY_ENABLED,
                            new WireValue.BoolValue(next.enabled()))) {
                return false;
            }
        }
        if (!appendAttributeChanges(previous, next, out)) {
            return false;
        }
        return appendChanges(previous.children(), next.children(), out);
    }

    private static boolean appendAttributeChanges(UiElement previous, UiElement next,
            List<ElementUpdate.Entry> out) {
        Map<String, Object> before = previous.attributes();
        Map<String, Object> after = next.attributes();
        if (before.equals(after)) {
            return true;
        }
        if ("text_field".equals(next.type())) {
            // The client's EditBox owns text, selection and focus; patching attributes under
            // it would leave the widget showing stale text.
            return false;
        }
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            Object oldValue = before.get(key);
            Object newValue = after.get(key);
            if (Objects.equals(oldValue, newValue)) {
                continue;
            }
            Integer property = propertyOf(key);
            if (property == null) {
                return false;
            }
            if (property == ElementUpdate.PROPERTY_TEXT && newValue == null) {
                // "text" and "runs" share one property id; a removal cannot say which key
                // went away, so a full document is the only unambiguous answer.
                return false;
            }
            var wire = WireValues.toWire(newValue);
            if (wire.isEmpty()) {
                return false;
            }
            out.add(new ElementUpdate.Entry(next.id(), property, wire.get()));
        }
        return true;
    }

    private static Integer propertyOf(String attribute) {
        return switch (attribute) {
            case "text", "runs" -> ElementUpdate.PROPERTY_TEXT;
            case "texture" -> ElementUpdate.PROPERTY_TEXTURE;
            case "item" -> ElementUpdate.PROPERTY_ITEM;
            case "progress" -> ElementUpdate.PROPERTY_PROGRESS;
            case "value" -> ElementUpdate.PROPERTY_VALUE;
            case "tooltip" -> ElementUpdate.PROPERTY_TOOLTIP;
            case "style" -> ElementUpdate.PROPERTY_STYLE;
            case "selected" -> ElementUpdate.PROPERTY_SELECTED;
            case "invalid" -> ElementUpdate.PROPERTY_INVALID;
            case "checked" -> ElementUpdate.PROPERTY_CHECKED;
            case "scroll_y" -> ElementUpdate.PROPERTY_SCROLL_Y;
            case "color" -> ElementUpdate.PROPERTY_COLOR;
            case "placeholder" -> ElementUpdate.PROPERTY_PLACEHOLDER;
            default -> null;
        };
    }

    private static boolean add(List<ElementUpdate.Entry> out, String elementId, int property,
            WireValue value) {
        out.add(new ElementUpdate.Entry(elementId, property, value));
        return true;
    }
}
