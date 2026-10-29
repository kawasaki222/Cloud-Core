package com.cloudcore.api.page;

import java.util.Map;
import java.util.Objects;

/**
 * Page-level refresh policy plus per-element overrides. An element is due when either its own
 * spec or the page default says so; the scheduler refreshes the whole page and lets the
 * document diff keep the update small.
 */
public record RefreshPlan(RefreshSpec page, Map<String, RefreshSpec> elements, boolean async) {

    public static final RefreshPlan NONE = new RefreshPlan(RefreshSpec.STATIC, Map.of(), false);

    public RefreshPlan {
        Objects.requireNonNull(page, "page");
        elements = Map.copyOf(elements);
    }

    public RefreshPlan(RefreshSpec page, Map<String, RefreshSpec> elements) {
        this(page, elements, false);
    }

    /** Returns the element override, or the page default when no override exists. */
    public RefreshSpec effectiveSpec(String elementId) {
        Objects.requireNonNull(elementId, "elementId");
        return elements.getOrDefault(elementId, page);
    }

    public boolean hasPeriodic() {
        if (page.periodic()) {
            return true;
        }
        for (RefreshSpec spec : elements.values()) {
            if (spec.periodic()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasEvent() {
        if (page.mode() == RefreshSpec.Mode.EVENT) {
            return true;
        }
        for (RefreshSpec spec : elements.values()) {
            if (spec.mode() == RefreshSpec.Mode.EVENT) {
                return true;
            }
        }
        return false;
    }

    /** True when some periodic spec lands on this tick counter (1-based). */
    public boolean due(long tickCounter) {
        if (page.periodic() && tickCounter % page.periodTicks() == 0) {
            return true;
        }
        for (RefreshSpec spec : elements.values()) {
            if (spec.periodic() && tickCounter % spec.periodTicks() == 0) {
                return true;
            }
        }
        return false;
    }
}
