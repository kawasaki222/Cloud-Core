package com.cloudcore.server.placeholder;

import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit server placeholder syntax; unknown tokens retain their authored spelling. */
public final class PlaceholderExpressions {
    private static final Pattern EXPLICIT = Pattern.compile(
            "\\$\\{placeholder:([^}\\r\\n]{1,128})\\}", Pattern.CASE_INSENSITIVE);

    private PlaceholderExpressions() {
    }

    public static String expand(String text, UnaryOperator<String> percentExpander) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(percentExpander, "percentExpander");
        // Expand ordinary percent tokens first, so an expansion's output is never interpreted
        // as another explicit expression in the same pass.
        StringBuilder result = new StringBuilder(text.length());
        Matcher matcher = EXPLICIT.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        int cursor = 0;
        do {
            append(result, text.substring(cursor, matcher.start()), percentExpander);
            String token = "%" + matcher.group(1) + "%";
            String replacement = percentExpander.apply(token);
            result.append(token.equals(replacement) ? matcher.group() : replacement);
            cursor = matcher.end();
        } while (matcher.find());
        append(result, text.substring(cursor), percentExpander);
        return result.toString();
    }

    private static void append(StringBuilder out, String chunk, UnaryOperator<String> expander) {
        if (!chunk.isEmpty()) {
            out.append(expander.apply(chunk));
        }
    }
}
