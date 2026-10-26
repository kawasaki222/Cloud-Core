package com.cloudcore.api.document;

import com.cloudcore.api.SurfaceType;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Structural validation for a downloaded UI document.
 *
 * <p>Codes are stable and use the {@code CC-DOC-*} namespace from diagnostics docs.
 */
public final class UiDocumentValidator {
    private static final int MAX_ELEMENTS = 10_000;
    private static final int MAX_DEPTH = 64;
    private static final Set<String> CONTAINER_INTERACTIONS =
            Set.of("managed", "readonly", "passthrough");
    private static final Set<String> FONT_PROVIDERS =
            Set.of("nanovg_ttf", "nanovg_bitmap", "native");
    private static final Set<String> FONT_SOURCES = Set.of("local", "mod", "resource");
    private static final Set<String> KNOWN_ELEMENT_TYPES = Set.of(
            "panel", "row", "column", "grid", "scroll", "overlay",
            "label", "image", "button", "icon_button", "text_field", "checkbox",
            "slider", "progress", "inventory_grid", "item_icon", "spacer");

    private UiDocumentValidator() {
    }

    public static ValidationResult validate(UiDocument document) {
        ValidationResultBuilder result = new ValidationResultBuilder();
        if (document.schema() != UiDocument.CURRENT_SCHEMA) {
            result.add(ValidationIssue.error("CC-DOC-003", "schema",
                    "Unsupported document schema: " + document.schema()));
        }
        if (document.id().isBlank()) {
            result.add(ValidationIssue.error("CC-DOC-010", "id", "Document id is blank"));
        }
        if (document.width() <= 0 || document.height() <= 0) {
            result.add(ValidationIssue.error("CC-DOC-012", "size",
                    "Document size must be positive"));
        }
        if (!(document.scale() > 0.0f) || document.scale() > 4.0f) {
            result.add(ValidationIssue.error("CC-DOC-013", "scale",
                    "Scale must be in (0, 4]"));
        }
        if (document.title().runs().isEmpty()) {
            result.add(ValidationIssue.warning("CC-DOC-019", "title", "Title has no text runs"));
        }
        if (document.surface() == SurfaceType.CONTAINER) {
            if (document.container() == null) {
                result.add(ValidationIssue.error("CC-DOC-011", "container",
                        "Container documents require a container spec"));
            } else if (!CONTAINER_INTERACTIONS.contains(
                    document.container().interaction().toLowerCase(Locale.ROOT))) {
                result.add(ValidationIssue.error("CC-DOC-017", "container.interaction",
                        "Unknown interaction: " + document.container().interaction()));
            }
        } else if (document.container() != null) {
            result.add(ValidationIssue.warning("CC-DOC-016", "container",
                    "Container spec is ignored for surface " + document.surface()));
        }

        document.fonts().forEach((name, font) -> {
            if (name.isBlank()) {
                result.add(ValidationIssue.error("CC-DOC-020", "fonts", "Font name is blank"));
            }
            if (!FONT_PROVIDERS.contains(font.provider())) {
                result.add(ValidationIssue.error("CC-DOC-018", "fonts." + name + ".provider",
                        "Unknown font provider: " + font.provider()));
            }
            if (!FONT_SOURCES.contains(font.source())) {
                result.add(ValidationIssue.error("CC-DOC-018", "fonts." + name + ".source",
                        "Unknown font source: " + font.source()));
            }
        });

        Set<String> ids = new HashSet<>();
        ElementWalk walk = new ElementWalk(result, ids, document.surface());
        for (int index = 0; index < document.elements().size(); index++) {
            walk.visit(document.elements().get(index), "elements[" + index + "]", 1);
        }
        return result.build();
    }

    private static final class ElementWalk {
        private final ValidationResultBuilder result;
        private final Set<String> ids;
        private final SurfaceType surface;
        private int count;

        private ElementWalk(ValidationResultBuilder result, Set<String> ids, SurfaceType surface) {
            this.result = result;
            this.ids = ids;
            this.surface = surface;
        }

        private void visit(UiElement element, String path, int depth) {
            if (count >= MAX_ELEMENTS) {
                return;
            }
            count++;
            if (depth > MAX_DEPTH) {
                result.add(ValidationIssue.error("CC-DOC-021", path,
                        "Element nesting exceeds " + MAX_DEPTH));
                return;
            }
            if (element.id().isBlank()) {
                result.add(ValidationIssue.error("CC-DOC-010", path + ".id", "Element id is blank"));
            } else if (!ids.add(element.id())) {
                result.add(ValidationIssue.error("CC-DOC-007", path + ".id",
                        "Duplicate element id: " + element.id()));
            }
            if (element.type().isBlank()) {
                result.add(ValidationIssue.error("CC-DOC-014", path + ".type", "Element type is blank"));
            } else if (!KNOWN_ELEMENT_TYPES.contains(element.type())) {
                result.add(ValidationIssue.warning("CC-DOC-014", path + ".type",
                        "Unknown element type (kept for forward compatibility): " + element.type()));
            }
            if (element.type().equals("inventory_grid")
                    && surface != SurfaceType.CONTAINER
                    && surface != SurfaceType.PLAYER_INVENTORY) {
                result.add(ValidationIssue.error("CC-DOC-014", path + ".type",
                        "inventory_grid is not allowed on surface " + surface));
            }
            // A null dimension means intrinsic size; an explicitly empty string does not.
            if ("".equals(element.width()) || "".equals(element.height())) {
                result.add(ValidationIssue.error("CC-DOC-012", path + ".size",
                        "Width/height must not be empty strings"));
            }
            element.events().forEach((event, actionId) -> {
                if (event.isBlank()) {
                    result.add(ValidationIssue.error("CC-DOC-015", path + ".events",
                            "Event name is blank"));
                }
                if (actionId == null || actionId < 0) {
                    result.add(ValidationIssue.error("CC-DOC-015", path + ".events." + event,
                            "Action id must be non-negative"));
                }
            });
            for (int index = 0; index < element.children().size(); index++) {
                visit(element.children().get(index), path + ".children[" + index + "]", depth + 1);
            }
        }
    }

    private static final class ValidationResultBuilder {
        private final List<ValidationIssue> issues = new java.util.ArrayList<>();

        private void add(ValidationIssue issue) {
            issues.add(issue);
        }

        private ValidationResult build() {
            return new ValidationResult(issues);
        }
    }
}
