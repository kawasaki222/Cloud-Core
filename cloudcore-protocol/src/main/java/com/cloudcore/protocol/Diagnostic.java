package com.cloudcore.protocol;

import java.util.Objects;

/**
 * Server-side diagnostic summary delivered to the client log.
 */
public record Diagnostic(
        int severity,
        String code,
        int traceId,
        String scope,
        String message,
        String detail) {

    public Diagnostic {
        if (severity < DiagnosticSeverity.INFO || severity > DiagnosticSeverity.FATAL) {
            throw new IllegalArgumentException("severity out of range: " + severity);
        }
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(detail, "detail");
    }
}
