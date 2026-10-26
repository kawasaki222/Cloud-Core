package com.cloudcore.api.document;

import java.util.List;
import java.util.Objects;

public record ValidationResult(List<ValidationIssue> issues) {

    public ValidationResult {
        Objects.requireNonNull(issues, "issues");
        issues = List.copyOf(issues);
    }

    public boolean valid() {
        return issues.stream().noneMatch(issue -> issue.severity() == ValidationSeverity.ERROR);
    }

    public List<ValidationIssue> errors() {
        return issues.stream()
                .filter(issue -> issue.severity() == ValidationSeverity.ERROR)
                .toList();
    }
}
