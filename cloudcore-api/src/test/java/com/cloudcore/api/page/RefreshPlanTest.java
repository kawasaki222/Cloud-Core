package com.cloudcore.api.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RefreshPlanTest {

    @Test
    void elementOverrideReplacesPageDefaultForEffectivePolicy() {
        RefreshSpec page = new RefreshSpec(RefreshSpec.Mode.INTERVAL, 20);
        RefreshSpec override = new RefreshSpec(RefreshSpec.Mode.MANUAL, 0);
        RefreshPlan plan = new RefreshPlan(page, Map.of("balance", override));

        assertEquals(page, plan.effectiveSpec("other"));
        assertEquals(override, plan.effectiveSpec("balance"));
        assertTrue(plan.hasPeriodic());
        assertFalse(plan.due(10), "a page default is not applied to an overridden element");
    }

    @Test
    void periodicAndEventPoliciesAreDetectedAcrossDefaultsAndOverrides() {
        RefreshPlan eventPlan = new RefreshPlan(RefreshSpec.STATIC,
                Map.of("status", new RefreshSpec(RefreshSpec.Mode.EVENT, 0)));
        RefreshPlan periodicPlan = new RefreshPlan(RefreshSpec.STATIC,
                Map.of("status", new RefreshSpec(RefreshSpec.Mode.INTERVAL, 3)));

        assertTrue(eventPlan.hasEvent());
        assertFalse(eventPlan.hasPeriodic());
        assertTrue(periodicPlan.hasPeriodic());
        assertTrue(periodicPlan.due(3));
        assertFalse(periodicPlan.due(2));
    }

    @Test
    void refreshSpecRejectsFractionalAndOverflowTicks() {
        assertThrows(IllegalArgumentException.class,
                () -> RefreshSpec.parse("interval", 1.5, "test"));
        assertThrows(IllegalArgumentException.class,
                () -> RefreshSpec.parse("interval", new BigDecimal("2147483648"), "test"));
        assertThrows(IllegalArgumentException.class,
                () -> RefreshSpec.parse("interval", "1.0", "test"));
        assertEquals(20, RefreshSpec.parse("interval", null, "test").ticks());
    }
}
