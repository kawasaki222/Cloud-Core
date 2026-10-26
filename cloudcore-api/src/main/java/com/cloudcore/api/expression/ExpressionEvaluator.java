package com.cloudcore.api.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluates the small expression language used by page actions:
 * numbers, {@code + - * / %}, parentheses, comparisons ({@code == != < <= > >=}),
 * boolean {@code && || !}, and identifiers resolved against a variable map
 * ({@code state.balance}, {@code value}, {@code item.price}).
 *
 * <p>Values are {@link Double} or {@link Boolean} or {@link String}. Anything the parser does
 * not understand throws {@link ExpressionException}; callers keep the literal text then.
 *
 * <p>Pure Java, no server state: unit-tested in isolation.
 */
public final class ExpressionEvaluator {

    public static final class ExpressionException extends RuntimeException {
        public ExpressionException(String message) {
            super(message);
        }
    }

    private final String source;
    private final Map<String, Object> variables;
    private List<Token> tokens;
    private int position;

    private ExpressionEvaluator(String source, Map<String, Object> variables) {
        this.source = source;
        this.variables = variables;
    }

    /** @return the evaluated value ({@link Double}, {@link Boolean} or {@link String}) */
    public static Object evaluate(String expression, Map<String, Object> variables) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(variables, "variables");
        ExpressionEvaluator evaluator = new ExpressionEvaluator(expression, variables);
        evaluator.tokens = tokenize(expression);
        evaluator.position = 0;
        Object value = evaluator.parseOr();
        if (evaluator.position < evaluator.tokens.size()) {
            throw new ExpressionException("Unexpected token near " + evaluator.source);
        }
        return value;
    }

    /** Convenience: evaluate and convert to text the way actions interpolate values. */
    public static String evaluateText(String expression, Map<String, Object> variables) {
        return text(evaluate(expression, variables));
    }

    public static String text(Object value) {
        if (value instanceof Double number) {
            return number == Math.floor(number) && !number.isInfinite()
                    ? String.valueOf(number.longValue())
                    : String.valueOf(number);
        }
        return String.valueOf(value);
    }

    // ---- parsing -------------------------------------------------------

    private Object parseOr() {
        Object left = parseAnd();
        while (match("||")) {
            Object right = parseAnd();
            left = truthy(left) || truthy(right);
        }
        return left;
    }

    private Object parseAnd() {
        Object left = parseComparison();
        while (match("&&")) {
            Object right = parseComparison();
            left = truthy(left) && truthy(right);
        }
        return left;
    }

    private Object parseComparison() {
        Object left = parseAdditive();
        for (String operator : new String[]{"==", "!=", "<=", ">=", "<", ">"}) {
            if (match(operator)) {
                Object right = parseAdditive();
                return compare(operator, left, right);
            }
        }
        return left;
    }

    private Object parseAdditive() {
        Object left = parseMultiplicative();
        while (true) {
            if (match("+")) {
                Object right = parseMultiplicative();
                if (left instanceof String || right instanceof String) {
                    left = text(left) + text(right);
                } else {
                    left = number(left) + number(right);
                }
            } else if (match("-")) {
                left = number(left) - number(parseMultiplicative());
            } else {
                return left;
            }
        }
    }

    private Object parseMultiplicative() {
        Object left = parseUnary();
        while (true) {
            if (match("*")) {
                left = number(left) * number(parseUnary());
            } else if (match("/")) {
                double divisor = number(parseUnary());
                if (divisor == 0.0) {
                    throw new ExpressionException("Division by zero");
                }
                left = number(left) / divisor;
            } else if (match("%")) {
                left = number(left) % number(parseUnary());
            } else {
                return left;
            }
        }
    }

    private Object parseUnary() {
        if (match("!")) {
            return !truthy(parseUnary());
        }
        if (match("-")) {
            return -number(parseUnary());
        }
        if (match("(")) {
            Object value = parseOr();
            expect(")");
            return value;
        }
        return parsePrimary();
    }

    private Object parsePrimary() {
        Token token = peek();
        if (token == null) {
            throw new ExpressionException("Unexpected end of expression");
        }
        position++;
        return switch (token.kind) {
            case NUMBER -> Double.parseDouble(token.text);
            case STRING -> token.text;
            case IDENTIFIER -> resolve(token.text);
            default -> throw new ExpressionException("Unexpected token " + token.text);
        };
    }

    private Object resolve(String name) {
        if ("true".equalsIgnoreCase(name)) {
            return true;
        }
        if ("false".equalsIgnoreCase(name)) {
            return false;
        }
        Object value = lookup(name);
        if (value == null) {
            return "";
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return String.valueOf(value);
    }

    private Object lookup(String path) {
        Object current = variables;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private boolean match(String operator) {
        Token token = peek();
        if (token != null && token.kind == Kind.OPERATOR && token.text.equals(operator)) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(String operator) {
        if (!match(operator)) {
            throw new ExpressionException("Expected " + operator);
        }
    }

    private Token peek() {
        return position < tokens.size() ? tokens.get(position) : null;
    }

    private static Boolean compare(String operator, Object left, Object right) {
        if (left instanceof String || right instanceof String) {
            String a = text(left);
            String b = text(right);
            return switch (operator) {
                case "==" -> a.equals(b);
                case "!=" -> !a.equals(b);
                default -> throw new ExpressionException("Cannot compare strings with " + operator);
            };
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            boolean a = truthy(left);
            boolean b = truthy(right);
            return switch (operator) {
                case "==" -> a == b;
                case "!=" -> a != b;
                default -> throw new ExpressionException("Cannot compare booleans with " + operator);
            };
        }
        double a = number(left);
        double b = number(right);
        return switch (operator) {
            case "==" -> a == b;
            case "!=" -> a != b;
            case "<" -> a < b;
            case "<=" -> a <= b;
            case ">" -> a > b;
            case ">=" -> a >= b;
            default -> throw new ExpressionException("Unknown operator " + operator);
        };
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0;
        }
        if (value instanceof String text) {
            return !text.isEmpty() && !"false".equalsIgnoreCase(text);
        }
        return value != null;
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException exception) {
                throw new ExpressionException("Not a number: " + text);
            }
        }
        if (value instanceof Boolean flag) {
            return flag ? 1.0 : 0.0;
        }
        throw new ExpressionException("Not a number: " + value);
    }

    // ---- tokens --------------------------------------------------------

    private enum Kind { NUMBER, STRING, IDENTIFIER, OPERATOR }

    private record Token(Kind kind, String text) {
    }

    private static List<Token> tokenize(String expression) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < expression.length()) {
            char character = expression.charAt(index);
            if (Character.isWhitespace(character)) {
                index++;
                continue;
            }
            if (Character.isDigit(character)
                    || (character == '.' && index + 1 < expression.length()
                    && Character.isDigit(expression.charAt(index + 1)))) {
                int start = index;
                while (index < expression.length()
                        && (Character.isDigit(expression.charAt(index))
                        || expression.charAt(index) == '.')) {
                    index++;
                }
                tokens.add(new Token(Kind.NUMBER, expression.substring(start, index)));
                continue;
            }
            if (Character.isLetter(character) || character == '_') {
                int start = index;
                while (index < expression.length()
                        && (Character.isLetterOrDigit(expression.charAt(index))
                        || expression.charAt(index) == '_'
                        || expression.charAt(index) == '.')) {
                    index++;
                }
                tokens.add(new Token(Kind.IDENTIFIER, expression.substring(start, index)));
                continue;
            }
            if (character == '\'' || character == '"') {
                char quote = character;
                int start = ++index;
                StringBuilder builder = new StringBuilder();
                while (index < expression.length() && expression.charAt(index) != quote) {
                    builder.append(expression.charAt(index++));
                }
                if (index >= expression.length()) {
                    throw new ExpressionException("Unterminated string");
                }
                index++;
                tokens.add(new Token(Kind.STRING, builder.toString()));
                continue;
            }
            String two = index + 1 < expression.length()
                    ? expression.substring(index, index + 2) : "";
            if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")
                    || two.equals("&&") || two.equals("||")) {
                tokens.add(new Token(Kind.OPERATOR, two));
                index += 2;
                continue;
            }
            String single = String.valueOf(character).toLowerCase(Locale.ROOT);
            if ("+-*/%<>!()".contains(single)) {
                tokens.add(new Token(Kind.OPERATOR, single));
                index++;
                continue;
            }
            throw new ExpressionException("Unexpected character '" + character + "'");
        }
        return tokens;
    }
}
