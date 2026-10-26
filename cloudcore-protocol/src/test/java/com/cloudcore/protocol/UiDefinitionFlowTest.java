package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiDocumentJson;
import com.cloudcore.api.document.UiDocumentValidator;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UiDefinitionFlowTest {

    @Test
    void definitionSurvivesCompressedEnvelopeAndValidation() throws Exception {
        String json = """
                {
                  "schema": 1,
                  "id": "demo",
                  "surface": "custom_screen",
                  "title": { "runs": [ { "text": "Cloud Core" } ] },
                  "size": [320, 180],
                  "elements": [
                    { "id": "root", "type": "panel", "width": "fill", "height": "fill" }
                  ]
                }
                """;
        UiDefinition definition = new UiDefinition("demo", 1, 0,
                json.getBytes(StandardCharsets.UTF_8));
        byte[] body = UiCodec.encodeDefinition(definition, Limits.S2C);
        Frame frame = new Frame(ProtocolVersion.MAJOR, FrameFlags.COMPRESSED,
                Opcode.UI_DEFINITION, body);
        Frame decodedFrame = FrameCodec.decode(
                FrameCodec.encode(frame, Limits.S2C), Limits.S2C);
        UiDefinition decoded = UiCodec.decodeDefinition(decodedFrame.body(), Limits.S2C);

        UiDocument document = UiDocumentJson.parse(decoded.data());
        assertEquals("demo", document.id());
        assertTrue(UiDocumentValidator.validate(document).valid());
    }
}
