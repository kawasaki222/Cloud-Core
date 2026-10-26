package com.cloudcore.api.document;

import java.util.Objects;

public record ValidationIssue(
        ValidationSeverity severity,
        String code,
        String path,
        String message) {

    public ValidationIssue {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
    }

    public static ValidationIssue error(String code, String path, String message) {
        return new ValidationIssue(ValidationSeverity.ERROR, code, path, message);
    }

    public static ValidationIssue warning(String code, String path, String message) {
        return new ValidationIssue(ValidationSeverity.WARNING, code, path, message);
    }
}
