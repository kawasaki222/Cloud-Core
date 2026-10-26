package com.cloudcore.api.document;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.page.PageLoadException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The client parses this JSON from the network, so random or hostile bytes must only ever
 * produce {@link PageLoadException} — never an unchecked exception that would take the
 * render thread down.
 */
class UiDocumentJsonFuzzTest {
    private static final String ALPHABET =
            "{}[]\":,0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ -_.";

    @Test
    void randomJsonOnlyFailsWithPageLoadException() {
        Random random = new Random(0xD0C5);
        int rejected = 0;
        for (int index = 0; index < 20_000; index++) {
            int length = random.nextInt(64);
            StringBuilder builder = new StringBuilder(length);
            for (int character = 0; character < length; character++) {
                builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            try {
                UiDocumentJson.parse(builder.toString());
            } catch (PageLoadException expected) {
                rejected++;
            }
        }
        assertTrue(rejected > 0, "garbage must be rejected");
    }

    @Test
    void everyTruncationOfAValidDocumentFailsCleanly() {
        UiDocument document = new UiDocument(1, "fuzz", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "dark", 100, 100, 1.0f, null, Map.of(),
                Map.of(), List.of());
        String json = UiDocumentJson.write(document);
        for (int cut = 0; cut < json.length(); cut++) {
            try {
                UiDocumentJson.parse(json.substring(0, cut));
            } catch (PageLoadException expected) {
                // Truncation is a malformed document; an unchecked exception would be a bug.
            }
        }
        assertTrue(json.length() > 0);
    }
}
