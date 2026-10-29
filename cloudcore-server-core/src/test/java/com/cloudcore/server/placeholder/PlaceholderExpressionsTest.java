package com.cloudcore.server.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaceholderExpressionsTest {

    @Test
    void expandsExplicitTokensThroughThePercentExpander() {
        Map<String, String> values = Map.of("%player_name%", "Nono");

        String result = PlaceholderExpressions.expand("Hi ${placeholder:player_name}!",
                token -> values.getOrDefault(token, token));

        assertEquals("Hi Nono!", result);
    }

    @Test
    void expandsPercentTokensInTheSameText() {
        String result = PlaceholderExpressions.expand(
                "%prefix% ${placeholder:player_name}", token -> token.replace("%prefix%", "[Core]")
                        .replace("%player_name%", "Nono"));

        assertEquals("[Core] Nono", result);
    }

    @Test
    void unknownTokensKeepTheirAuthoredSpelling() {
        String text = "Hi ${placeholder:missing} and ${placeholder:other}";

        assertEquals(text, PlaceholderExpressions.expand(text, token -> token));
    }

    @Test
    void expansionOutputIsNotInterpretedAgain() {
        String text = "${placeholder:a}";
        String result = PlaceholderExpressions.expand(text,
                token -> "${placeholder:b}");

        assertEquals("${placeholder:b}", result);
    }

    @Test
    void textWithoutPlaceholdersIsReturnedUnchanged() {
        String text = "plain text";
        String result = PlaceholderExpressions.expand(text, token -> {
            throw new AssertionError("expander must not be called");
        });

        assertEquals(text, result);
    }
}
