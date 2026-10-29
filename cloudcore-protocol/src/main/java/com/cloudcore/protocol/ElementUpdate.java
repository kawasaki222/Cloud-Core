package com.cloudcore.protocol;

import java.util.List;
import java.util.Objects;

/**
 * An incremental replacement for one document revision. Only elements whose properties
 * changed are listed; everything else stays as the client already has it.
 *
 * <p>Revision ordering is strict: an update applies only to {@code revision == current + 1}.
 * A gap means a previous update was lost, so the client keeps its current document and waits
 * for a full {@code UI_DEFINITION}.
 */
public record ElementUpdate(String uiId, int revision, List<Entry> entries) {

    public static final int PROPERTY_TEXT = 1;
    public static final int PROPERTY_VISIBLE = 2;
    public static final int PROPERTY_ENABLED = 3;
    public static final int PROPERTY_TEXTURE = 4;
    public static final int PROPERTY_ITEM = 5;
    public static final int PROPERTY_PROGRESS = 6;
    public static final int PROPERTY_VALUE = 7;
    public static final int PROPERTY_TOOLTIP = 8;
    public static final int PROPERTY_STYLE = 9;
    public static final int PROPERTY_SELECTED = 10;
    public static final int PROPERTY_INVALID = 11;
    public static final int PROPERTY_CHECKED = 12;
    public static final int PROPERTY_SCROLL_Y = 13;
    public static final int PROPERTY_COLOR = 14;
    public static final int PROPERTY_PLACEHOLDER = 15;

    /** One changed property; {@code value} is {@code WireValue.NullValue} when removed. */
    public record Entry(String elementId, int propertyId, WireValue value) {
        public Entry {
            Objects.requireNonNull(elementId, "elementId");
            Objects.requireNonNull(value, "value");
            if (elementId.isBlank()) {
                throw new IllegalArgumentException("elementId must not be blank");
            }
            if (propertyId < 0 || propertyId > 0xFF) {
                throw new IllegalArgumentException("propertyId out of range: " + propertyId);
            }
        }
    }

    public ElementUpdate {
        Objects.requireNonNull(uiId, "uiId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        entries = List.copyOf(entries);
    }
}
