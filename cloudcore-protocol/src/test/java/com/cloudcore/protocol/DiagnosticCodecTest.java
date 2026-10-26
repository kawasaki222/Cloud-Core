package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DiagnosticCodecTest {

    @Test
    void roundTripsWithUtf8Fields() {
        Diagnostic diagnostic = new Diagnostic(DiagnosticSeverity.ERROR, "CC-DOC-003",
                0x12345678, "page", "文档校验失败", "elements[2].text");
        byte[] body = DiagnosticCodec.encode(diagnostic, Limits.S2C);
        assertEquals(diagnostic, DiagnosticCodec.decode(body, Limits.S2C));
    }

    @Test
    void rejectsInvalidSeverity() {
        byte[] body = DiagnosticCodec.encode(new Diagnostic(DiagnosticSeverity.INFO, "CC-X",
                1, "ui", "m", "d"), Limits.S2C);
        body[0] = 4;
        assertThrows(ProtocolException.class, () -> DiagnosticCodec.decode(body, Limits.S2C));
    }
}
