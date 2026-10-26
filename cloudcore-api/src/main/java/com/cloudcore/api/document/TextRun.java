package com.cloudcore.api.document;

import java.util.Objects;

/**
 * One styled text segment in a downloaded UI document.
 */
public record TextRun(
        String text,
        String color,
        boolean bold,
        boolean italic,
        boolean underline,
        boolean strikethrough,
        boolean obfuscated,
        String font) {

    public TextRun {
        Objects.requireNonNull(text, "text");
    }

    public static TextRun plain(String text) {
        return new TextRun(text, null, false, false, false, false, false, null);
    }
}
