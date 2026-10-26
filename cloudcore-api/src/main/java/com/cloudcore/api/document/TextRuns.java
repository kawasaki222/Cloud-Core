package com.cloudcore.api.document;

import java.util.List;
import java.util.Objects;

/**
 * Ordered, already-evaluated text segments.
 */
public record TextRuns(List<TextRun> runs) {

    public TextRuns {
        Objects.requireNonNull(runs, "runs");
        runs = List.copyOf(runs);
    }

    public static TextRuns plain(String text) {
        return new TextRuns(List.of(TextRun.plain(text)));
    }

    public String plainText() {
        StringBuilder builder = new StringBuilder();
        for (TextRun run : runs) {
            builder.append(run.text());
        }
        return builder.toString();
    }
}
