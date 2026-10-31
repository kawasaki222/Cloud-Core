package com.cloudcore.client.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/**
 * Greedy line breaking for {@code label.wrap} / {@code max_lines}.
 *
 * <p>Measures with the font the active backend draws with (see {@link Measure}), so a line
 * never exceeds the box it was wrapped for. Latin words move to the next line whole; a word
 * wider than the line, and every CJK character, breaks between code points. Splitting happens
 * on code points, so surrogate pairs (emoji) are never cut in half.
 *
 * <p>Render thread only: vanilla {@link Font#width} and NanoVG font handles both live there.
 */
final class TextWrap {
    private static final String NEWLINE = String.valueOf((char) 10);

    private TextWrap() {
    }

    private static final java.util.concurrent.atomic.AtomicReference<Measure> MEASURER =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * How text is measured. The backends install one that matches what they draw with:
     * NanoVG measures TTF text with its own font handle, the native backend uses vanilla
     * {@link Font#width}. Without one (headless tests) text is treated as fixed-width so the
     * wrapping rules can still be exercised.
     */
    interface Measure {
        int width(String text);

        int lineHeight();
    }

    static void install(Measure measure) {
        MEASURER.set(measure);
    }

    /** Vanilla font metrics; used by the native backend. */
    static Measure vanillaMeasure() {
        return new Measure() {
            @Override
            public int width(String text) {
                return Minecraft.getInstance().font.width(Component.literal(text));
            }

            @Override
            public int lineHeight() {
                return Minecraft.getInstance().font.lineHeight + 1;
            }
        };
    }

    private static Measure measure() {
        Measure installed = MEASURER.get();
        return installed != null ? installed : HEADLESS;
    }

    /** 6 px per character, 9 px per line: enough for deterministic rule tests. */
    private static final Measure HEADLESS = new Measure() {
        @Override
        public int width(String text) {
            return text.codePointCount(0, text.length()) * 6;
        }

        @Override
        public int lineHeight() {
            return 9;
        }
    };

    /** Width of {@code text} under the installed measure. */
    static int width(String text) {
        return text == null ? 0 : measure().width(text);
    }

    /** Fixed-width estimate for callers that have no font yet (6 px per code point). */
    static int fallbackWidth(String text) {
        return text == null ? 0 : HEADLESS.width(text);
    }

    /** Height of one wrapped line as the installed measure draws it. */
    static int lineHeight(String font, float size) {
        return Math.max(1, measure().lineHeight());
    }

    /**
     * Splits {@code text} into lines no wider than {@code width} pixels. Explicit newlines
     * start a new line; a blank line in the middle is kept.
     *
     * @param maxLines stop after this many lines; 0 = all
     */
    static List<String> wrap(String text, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        if (width <= 0) {
            lines.addAll(List.of(text.split(NEWLINE, -1)));
            return lines;
        }
        for (String paragraph : text.split(NEWLINE, -1)) {
            wrapParagraph(paragraph, width, maxLines, lines);
            if (maxLines > 0 && lines.size() >= maxLines) {
                break;
            }
        }
        return lines;
    }

    private static void wrapParagraph(String paragraph, int width, int maxLines,
            List<String> lines) {
        if (paragraph.isEmpty()) {
            lines.add("");
            return;
        }
        int[] cps = paragraph.codePoints().toArray();
        StringBuilder current = new StringBuilder();
        for (int[] span : fitSpans(cps, width)) {
            String piece = spanText(cps, span);
            int trailing = spaces(piece);
            boolean spacesOnly = trailing == piece.length();
            int widthOf = measure().width(piece.substring(0, piece.length() - trailing));
            // `piece` already ends with its spaces, so the line keeps them until it is
            // emitted; that is what keeps the width check honest ("alpha beta " is 6+1).
            if (current.length() > 0 && !spacesOnly
                    && measure().width(current.toString()) + widthOf > width) {
                lines.add(current.toString().strip());
                current.setLength(0);
                if (maxLines > 0 && lines.size() >= maxLines) {
                    return;
                }
            }
            if (spacesOnly && current.length() == 0) {
                continue;
            }
            current.append(piece);
        }
        if (!current.toString().isBlank()) {
            lines.add(current.toString().strip());
        }
    }

    /** Unbreakable runs narrowed to the line width; wider words are cut between code points. */
    private static List<int[]> fitSpans(int[] cps, int width) {
        List<int[]> spans = new ArrayList<>();
        for (int[] span : segment(cps)) {
            if (measure().width(spanText(cps, span)) <= width) {
                spans.add(span);
            } else {
                alongCodePoints(cps, span, width, spans);
            }
        }
        return spans;
    }

    /**
     * Unbreakable runs: every CJK character, and each Latin word together with the spaces
     * that follow it (that is where a line may break without losing a blank).
     */
    private static List<int[]> segment(int[] cps) {
        List<int[]> spans = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < cps.length; index++) {
            if (index > start && isCjk(cps[index])) {
                spans.add(new int[]{start, index});
                start = index;
            }
            if (cps[index] == ' ' && index + 1 < cps.length) {
                spans.add(new int[]{start, index + 1});
                start = index + 1;
            }
        }
        if (start < cps.length) {
            spans.add(new int[]{start, cps.length});
        }
        return spans;
    }

    /** Last resort for a run wider than the line: cut between code points. */
    private static void alongCodePoints(int[] cps, int[] span, int width, List<int[]> out) {
        int from = span[0];
        int used = 0;
        for (int index = span[0]; index < span[1]; index++) {
            int glyphWidth = measure().width(spanText(cps, new int[]{index, index + 1}));
            if (index > from && used + glyphWidth > width) {
                out.add(new int[]{from, index});
                from = index;
                used = 0;
            }
            used += glyphWidth;
        }
        if (from < span[1]) {
            out.add(new int[]{from, span[1]});
        }
    }

    private static String spanText(int[] cps, int[] span) {
        return new String(cps, span[0], span[1] - span[0]);
    }

    /** Number of trailing spaces in {@code text}. */
    private static int spaces(String text) {
        int count = 0;
        while (count < text.length() && text.charAt(text.length() - 1 - count) == ' ') {
            count++;
        }
        return count;
    }

    /** CJK, kana, hangul and fullwidth forms break between characters. */
    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF)
                || (codePoint >= 0x3000 && codePoint <= 0x303F);
    }

    /** {@code max_lines}: a positive number caps the lines; anything else means no cap. */
    static int maxLines(com.cloudcore.api.document.UiElement element) {
        Object raw = element.attributes().get("max_lines");
        int value = raw instanceof Number number ? number.intValue()
                : raw instanceof String text ? parse(text) : 0;
        return Math.max(0, Math.min(64, value));
    }

    private static int parse(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
