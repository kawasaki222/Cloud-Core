package com.cloudcore.api.expression;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Random input must never escape as anything but {@link ExpressionEvaluator.ExpressionException}:
 * the evaluator runs on server data, so an unchecked exception would break the action loop.
 */
class ExpressionEvaluatorFuzzTest {
    private static final String ALPHABET =
            "0123456789 +-*/%<>=!&|()'\"abcxyz_ .:$#[]{}";
    private static final Map<String, Object> VARS = Map.of(
            "state", Map.of("balance", 120, "name", "Ada"),
            "value", "sword",
            "checked", true);

    @Test
    void randomStringsOnlyFailWithExpressionException() {
        Random random = new Random(0xC10D_C0DE);
        int evaluated = 0;
        int rejected = 0;
        for (int index = 0; index < 20_000; index++) {
            int length = random.nextInt(24);
            StringBuilder builder = new StringBuilder(length);
            for (int character = 0; character < length; character++) {
                builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String expression = builder.toString().trim();
            if (expression.isEmpty()) {
                continue;
            }
            try {
                ExpressionEvaluator.evaluate(expression, VARS);
                evaluated++;
            } catch (ExpressionEvaluator.ExpressionException expected) {
                rejected++;
            }
        }
        assertTrue(evaluated > 0, "some random inputs should parse");
        assertTrue(rejected > 0, "some random inputs should be rejected");
    }
}
