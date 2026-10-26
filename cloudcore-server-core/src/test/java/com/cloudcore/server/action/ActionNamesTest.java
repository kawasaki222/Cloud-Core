package com.cloudcore.server.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ActionNamesTest {

    @Test
    void normalisesCaseAndWhitespace() {
        assertEquals("rpg_buy", ActionNames.normalise(" RPG_Buy "));
        assertEquals("quest.accept", ActionNames.normalise("quest.accept"));
        assertEquals("a-1", ActionNames.normalise("a-1"));
    }

    @Test
    void rejectsEmptyIllegalAndOverlongNames() {
        assertThrows(IllegalArgumentException.class, () -> ActionNames.normalise(""));
        assertThrows(IllegalArgumentException.class, () -> ActionNames.normalise("buy now"));
        assertThrows(IllegalArgumentException.class, () -> ActionNames.normalise("buy/now"));
        assertThrows(IllegalArgumentException.class, () -> ActionNames.normalise("买"));
        assertThrows(IllegalArgumentException.class,
                () -> ActionNames.normalise("a".repeat(65)));
        assertThrows(NullPointerException.class, () -> ActionNames.normalise(null));
    }

    @Test
    void isValidMirrorsNormalise() {
        assertEquals(64, "a".repeat(64).length());
        assertTrue(ActionNames.isValid("a".repeat(64)));
        assertTrue(ActionNames.isValid("RPG_Buy"));
        assertFalse(ActionNames.isValid("buy now"));
        assertFalse(ActionNames.isValid(""));
        assertFalse(ActionNames.isValid(null));
    }
}
