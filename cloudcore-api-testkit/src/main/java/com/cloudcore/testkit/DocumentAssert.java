package com.cloudcore.testkit;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiDocumentValidator;
import com.cloudcore.api.document.UiElement;
import com.cloudcore.api.document.UiElements;
import com.cloudcore.api.document.ValidationIssue;
import com.cloudcore.api.document.ValidationResult;
import java.util.stream.Collectors;

/**
 * Assertions for addon-built documents. Plain {@code AssertionError}s so they work with JUnit
 * 5, JUnit 4 and a bare main method alike.
 */
public final class DocumentAssert {

    private DocumentAssert() {
    }

    public static void assertValid(UiDocument document) {
        ValidationResult result = UiDocumentValidator.validate(document);
        if (!result.valid()) {
            String problems = result.issues().stream()
                    .map(ValidationIssue::toString)
                    .collect(Collectors.joining(", "));
            throw new AssertionError("Document '" + document.id() + "' is invalid: " + problems);
        }
    }

    public static UiElement assertElement(UiDocument document, String elementId, String type) {
        UiElement element = UiElements.findById(document, elementId);
        if (element == null) {
            throw new AssertionError("No element '" + elementId + "' in document '"
                    + document.id() + "'");
        }
        if (!type.equals(element.type())) {
            throw new AssertionError("Element '" + elementId + "' is " + element.type()
                    + ", expected " + type);
        }
        return element;
    }

    public static void assertText(UiDocument document, String elementId, String expected) {
        UiElement element = UiElements.findById(document, elementId);
        if (element == null) {
            throw new AssertionError("No element '" + elementId + "' in document '"
                    + document.id() + "'");
        }
        Object actual = element.attributes().get("text");
        if (!expected.equals(actual)) {
            throw new AssertionError("Element '" + elementId + "' text is '" + actual
                    + "', expected '" + expected + "'");
        }
    }

    public static void assertElementCount(UiDocument document, int expected) {
        if (document.elements().size() != expected) {
            throw new AssertionError("Document '" + document.id() + "' has "
                    + document.elements().size() + " top-level elements, expected " + expected);
        }
    }
}
