package com.cloudcore.server.placeholder;

import com.cloudcore.api.document.TextRun;
import com.cloudcore.api.document.TextRuns;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Rewrites the visible text of a document through a caller-supplied expander — on a Bukkit
 * server that is PlaceholderAPI, in tests it is a lambda. Only {@code text} and
 * {@code placeholder} attributes (and the document title) are expanded: colours, sizes,
 * events and texture references must never be rewritten from server data.
 *
 * <p>Expansion happens once per send (open and refresh), on the main thread; the client only
 * ever receives the result.
 */
public final class DocumentText {
    private DocumentText() {
    }

    public static UiDocument expand(UiDocument document, UnaryOperator<String> expander) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(expander, "expander");
        List<UiElement> elements = new ArrayList<>(document.elements().size());
        for (UiElement element : document.elements()) {
            elements.add(expandElement(element, expander));
        }
        return new UiDocument(document.schema(), document.id(), document.surface(),
                expandTitle(document.title(), expander), document.theme(), document.width(),
                document.height(), document.scale(), document.container(),
                document.surfaceOptions(), document.fonts(), elements);
    }

    private static TextRuns expandTitle(TextRuns title, UnaryOperator<String> expander) {
        List<TextRun> runs = new ArrayList<>(title.runs().size());
        for (TextRun run : title.runs()) {
            runs.add(new TextRun(expander.apply(run.text()), run.color(), run.bold(),
                    run.italic(), run.underline(), run.strikethrough(), run.obfuscated(),
                    run.font()));
        }
        return new TextRuns(runs);
    }

    private static UiElement expandElement(UiElement element, UnaryOperator<String> expander) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : element.attributes().entrySet()) {
            attributes.put(entry.getKey(), expandAttribute(entry.getKey(), entry.getValue(),
                    expander));
        }
        List<UiElement> children = new ArrayList<>(element.children().size());
        for (UiElement child : element.children()) {
            children.add(expandElement(child, expander));
        }
        return new UiElement(element.id(), element.type(), element.x(), element.y(),
                element.width(), element.height(), element.visible(), element.enabled(),
                element.z(), element.events(), children, attributes);
    }

    private static Object expandAttribute(String key, Object value,
            UnaryOperator<String> expander) {
        if (!"text".equals(key) && !"placeholder".equals(key)) {
            return value;
        }
        if (value instanceof String text) {
            return expander.apply(text);
        }
        if (value instanceof List<?> list) {
            return expandRuns(list, expander);
        }
        if (value instanceof Map<?, ?> map && map.get("runs") instanceof List<?> runs) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object entryValue = entry.getValue();
                result.put(String.valueOf(entry.getKey()),
                        "runs".equals(String.valueOf(entry.getKey()))
                                ? expandRuns((List<?>) entryValue, expander) : entryValue);
            }
            return result;
        }
        return value;
    }

    /** Expands the {@code text} field of each rich-text run, keeping its styling. */
    private static List<Object> expandRuns(List<?> runs, UnaryOperator<String> expander) {
        List<Object> result = new ArrayList<>(runs.size());
        for (Object run : runs) {
            if (run instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String runKey = String.valueOf(entry.getKey());
                    copy.put(runKey, "text".equals(runKey) && entry.getValue() instanceof String text
                            ? expander.apply(text) : entry.getValue());
                }
                result.add(copy);
            } else {
                result.add(run);
            }
        }
        return result;
    }
}
