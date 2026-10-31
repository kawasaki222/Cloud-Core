package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TextWrapTest {

    /** 7 px per code point, 10 px per line: readable numbers for assertions. */
    private static final TextWrap.Measure SEVEN = new TextWrap.Measure() {
        @Override
        public int width(String text) {
            return text.codePointCount(0, text.length()) * 7;
        }

        @Override
        public int lineHeight() {
            return 10;
        }
    };

    @BeforeEach
    void installMeasure() {
        TextWrap.install(SEVEN);
    }

    @AfterEach
    void clearMeasure() {
        TextWrap.install(null);
    }

    @Test
    void emptyTextHasNoLines() {
        assertTrue(TextWrap.wrap("", 100, 0).isEmpty());
        assertTrue(TextWrap.wrap(null, 100, 0).isEmpty());
    }

    @Test
    void textThatFitsStaysOnOneLine() {
        assertEquals(List.of("hi there"), TextWrap.wrap("hi there", 100, 0));
    }

    @Test
    void breaksLatinTextAtSpaces() {
        // 7 px per character, so 70 px holds ten; "alpha beta gamma" is 15 wide.
        List<String> lines = TextWrap.wrap("alpha beta gamma delta", 85, 0);
        assertEquals(List.of("alpha beta", "gamma delta"), lines);
    }

    @Test
    void breaksChineseBetweenCharacters() {
        List<String> lines = TextWrap.wrap("霜之哀伤饥渴难耐", 52, 0);
        assertEquals(List.of("霜之哀伤饥渴难", "耐"), lines);
    }

    @Test
    void neverSplitsSurrogatePairs() {
        List<String> lines = TextWrap.wrap("ab😀cd", 21, 0);
        assertEquals(List.of("ab😀", "cd"), lines);
    }

    @Test
    void respectsMaxLines() {
        List<String> lines = TextWrap.wrap("alpha beta gamma delta", 85, 2);
        assertEquals(List.of("alpha beta", "gamma delta"), lines);
    }

    @Test
    void zeroWidthKeepsTheTextWhole() {
        assertEquals(List.of("keep me"), TextWrap.wrap("keep me", 0, 0));
    }

    @Test
    void explicitNewlinesStartNewLines() {
        assertEquals(List.of("one", "two"), TextWrap.wrap("one\ntwo", 100, 0));
    }

    @Test
    void aWordLongerThanTheLineBreaksByCharacter() {
        List<String> lines = TextWrap.wrap("abcdefghijkl", 35, 0);
        assertEquals(List.of("abcde", "fghij", "kl"), lines);
    }
}
