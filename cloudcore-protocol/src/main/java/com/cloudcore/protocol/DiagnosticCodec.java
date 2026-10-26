package com.cloudcore.protocol;

import java.util.Objects;

public final class DiagnosticCodec {
    private DiagnosticCodec() {
    }

    public static byte[] encode(Diagnostic diagnostic, Limits limits) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSink sink = new ArrayByteSink();
        sink.writeByte(diagnostic.severity());
        ProtocolText.write(sink, diagnostic.code(), limits);
        sink.writeInt(diagnostic.traceId());
        ProtocolText.write(sink, diagnostic.scope(), limits);
        ProtocolText.write(sink, diagnostic.message(), limits);
        ProtocolText.write(sink, diagnostic.detail(), limits);
        return sink.toByteArray();
    }

    public static Diagnostic decode(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSource source = new ArrayByteSource(data);
        int severity = source.readUnsignedByte();
        if (severity > DiagnosticSeverity.FATAL) {
            throw new ProtocolException("Invalid diagnostic severity: " + severity);
        }
        String code = ProtocolText.read(source, limits);
        int traceId = source.readInt();
        String scope = ProtocolText.read(source, limits);
        String message = ProtocolText.read(source, limits);
        String detail = ProtocolText.read(source, limits);
        if (source.readableBytes() != 0) {
            throw new ProtocolException("Trailing bytes in DIAGNOSTIC: " + source.readableBytes());
        }
        return new Diagnostic(severity, code, traceId, scope, message, detail);
    }
}
