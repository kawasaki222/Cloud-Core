package com.cloudcore.api.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpressionEvaluatorTest {
    private static final Map<String, Object> VARS = Map.of(
            "state", Map.of("balance", 120, "clicks", 3, "name", "Ada"),
            "value", "sword",
            "checked", true);

    @Test
    void arithmeticAndPrecedence() {
        assertEquals(9.0, ExpressionEvaluator.evaluate("1 + 2 * 4", VARS));
        assertEquals(9.0, ExpressionEvaluator.evaluate("(1 + 2) * 3", VARS));
        assertEquals(5.0, ExpressionEvaluator.evaluate("10 % 3 + 4", VARS));
        assertEquals(118.0, ExpressionEvaluator.evaluate("state.balance - 2", VARS));
    }

    @Test
    void comparisonsProduceBooleans() {
        assertEquals(true, ExpressionEvaluator.evaluate("state.balance >= 100", VARS));
        assertEquals(false, ExpressionEvaluator.evaluate("state.clicks > 10", VARS));
        assertEquals(true, ExpressionEvaluator.evaluate("value == 'sword'", VARS));
    }

    @Test
    void booleanLogicAndNegation() {
        assertEquals(true,
                ExpressionEvaluator.evaluate("state.balance > 10 && !false", VARS));
        assertEquals(false,
                ExpressionEvaluator.evaluate("state.clicks > 10 || value == 'shield'", VARS));
        assertEquals(true, ExpressionEvaluator.evaluate("checked", VARS));
    }

    @Test
    void stringConcatenation() {
        assertEquals("Hi Ada", ExpressionEvaluator.evaluate("'Hi ' + state.name", VARS));
    }

    @Test
    void textFormattingDropsTrailingZero() {
        assertEquals("120", ExpressionEvaluator.evaluateText("state.balance", VARS));
        assertEquals("2", ExpressionEvaluator.evaluateText("state.balance / 60", VARS));
    }

    @Test
    void unknownIdentifiersAreEmptyStrings() {
        assertEquals("", ExpressionEvaluator.evaluate("state.missing", VARS));
    }

    @Test
    void malformedExpressionsThrow() {
        assertThrows(ExpressionEvaluator.ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("1 +", VARS));
        assertThrows(ExpressionEvaluator.ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("state.balance / 0", VARS));
        assertThrows(ExpressionEvaluator.ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("'unterminated", VARS));
        assertTrue(true);
    }

    @Test
    void malformedNumbersAreParseErrorsNotCrashes() {
        assertThrows(ExpressionEvaluator.ExpressionException.class,
                () -> ExpressionEvaluator.evaluate("1.2.3", VARS));
        assertThrows(ExpressionEvaluator.ExpressionException.class,
                () -> ExpressionEvaluator.evaluate(".5.6 + 1", VARS));
    }
}
