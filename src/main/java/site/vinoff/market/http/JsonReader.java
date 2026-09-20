package site.vinoff.market.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON parser, written because the marketplace now takes requests with structure in them.
 *
 * <p>The hand-rolled {@code Json.readString} that came before could find one string field in a flat object, which
 * was enough while every request was a UUID and nothing else. It is not enough for a list of slots, and it was also
 * quietly wrong: it copied the character after a backslash verbatim, so {@code "a\nb"} came back as {@code anb} and
 * {@code A} as {@code u0041}. Both of those matter the moment a player types a note.
 *
 * <p>Values come back as plain Java: {@link Map}, {@link List}, {@link String}, {@link Long}, {@link Double},
 * {@link Boolean} and null. Anything malformed is an {@link IllegalArgumentException}, which the API already turns
 * into a 400 rather than a 500.
 */
public final class JsonReader {

    /** Deep enough for any request the website sends, shallow enough that a hostile body cannot exhaust the stack. */
    private static final int MAX_DEPTH = 24;

    /** Longer than any honest request. The API is on loopback, but a bug on the other side should not be fatal. */
    private static final int MAX_LENGTH = 256 * 1024;

    private final String text;
    private int at;
    private int depth;

    private JsonReader(String text) {
        this.text = text;
    }

    /** Parses a whole document that must be an object. Nothing may follow it. */
    public static Map<String, Object> readObject(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("The request body is empty");
        }
        if (body.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("The request body is too large");
        }
        JsonReader reader = new JsonReader(body);
        reader.skipSpace();
        Object value = reader.value();
        reader.skipSpace();
        if (reader.at < reader.text.length()) {
            throw new IllegalArgumentException("Unexpected text after the JSON value");
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("The request body must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    // reading fields ------------------------------------------------------------------------------------------------

    /** A string that has to be there and has to have something in it. */
    public static String requireString(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Field " + field + " must be a non-empty string");
        }
        return text;
    }

    /** A string that may be missing, empty or null, all of which mean the same thing: nothing was said. */
    public static String optionalString(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Field " + field + " must be a string");
        }
        return text.isBlank() ? null : text;
    }

    public static int requireInt(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (value instanceof Long number) {
            return Math.toIntExact(number);
        }
        if (value instanceof Double number && number == Math.floor(number) && !number.isInfinite()) {
            return (int) (double) number;
        }
        throw new IllegalArgumentException("Field " + field + " must be a whole number");
    }

    /** A list of objects. An absent list is empty; a list with anything but objects in it is a refusal. */
    public static List<Map<String, Object>> objectList(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw new IllegalArgumentException("Field " + field + " must be a list");
        }
        List<Map<String, Object>> result = new ArrayList<>(items.size());
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException("Field " + field + " must be a list of objects");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) entry;
            result.add(typed);
        }
        return result;
    }

    // the parser ----------------------------------------------------------------------------------------------------

    private Object value() {
        if (at >= text.length()) {
            throw new IllegalArgumentException("The JSON ends too early");
        }
        char symbol = text.charAt(at);
        return switch (symbol) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        enter();
        Map<String, Object> result = new LinkedHashMap<>();
        at++;
        skipSpace();
        if (peek() == '}') {
            at++;
            depth--;
            return result;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw new IllegalArgumentException("A JSON object key must be a string");
            }
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            result.put(key, value());
            skipSpace();
            char next = peek();
            at++;
            if (next == '}') {
                depth--;
                return result;
            }
            if (next != ',') {
                throw new IllegalArgumentException("Expected a comma or a closing brace in a JSON object");
            }
        }
    }

    private List<Object> array() {
        enter();
        List<Object> result = new ArrayList<>();
        at++;
        skipSpace();
        if (peek() == ']') {
            at++;
            depth--;
            return result;
        }
        while (true) {
            skipSpace();
            result.add(value());
            skipSpace();
            char next = peek();
            at++;
            if (next == ']') {
                depth--;
                return result;
            }
            if (next != ',') {
                throw new IllegalArgumentException("Expected a comma or a closing bracket in a JSON array");
            }
        }
    }

    private String string() {
        at++;
        StringBuilder value = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw new IllegalArgumentException("A JSON string is not closed");
            }
            char symbol = text.charAt(at++);
            if (symbol == '"') {
                return value.toString();
            }
            if (symbol != '\\') {
                if (symbol < 0x20) {
                    throw new IllegalArgumentException("A raw control character in a JSON string");
                }
                value.append(symbol);
                continue;
            }
            if (at >= text.length()) {
                throw new IllegalArgumentException("A JSON string ends in a backslash");
            }
            char escaped = text.charAt(at++);
            switch (escaped) {
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case '/' -> value.append('/');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> value.append(unicode());
                default -> throw new IllegalArgumentException("Unknown escape \\" + escaped + " in a JSON string");
            }
        }
    }

    /** One \\uXXXX. Surrogate pairs need no special care: both halves are appended and Java pairs them up. */
    private char unicode() {
        if (at + 4 > text.length()) {
            throw new IllegalArgumentException("A short \\u escape in a JSON string");
        }
        String digits = text.substring(at, at + 4);
        at += 4;
        try {
            return (char) Integer.parseInt(digits, 16);
        } catch (NumberFormatException notHex) {
            throw new IllegalArgumentException("A \\u escape that is not hexadecimal: " + digits);
        }
    }

    private Object number() {
        int start = at;
        if (peek() == '-' || peek() == '+') {
            at++;
        }
        boolean whole = true;
        while (at < text.length()) {
            char symbol = text.charAt(at);
            if (symbol >= '0' && symbol <= '9') {
                at++;
            } else if (symbol == '.' || symbol == 'e' || symbol == 'E' || symbol == '-' || symbol == '+') {
                whole = false;
                at++;
            } else {
                break;
            }
        }
        String digits = text.substring(start, at);
        if (digits.isEmpty() || digits.equals("-")) {
            throw new IllegalArgumentException("Expected a JSON value at position " + start);
        }
        try {
            return whole ? (Object) Long.parseLong(digits) : (Object) Double.parseDouble(digits);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("Not a number: " + digits);
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw new IllegalArgumentException("Expected " + word + " at position " + at);
        }
        at += word.length();
        return value;
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw new IllegalArgumentException("The JSON is nested too deeply");
        }
    }

    private char peek() {
        if (at >= text.length()) {
            throw new IllegalArgumentException("The JSON ends too early");
        }
        return text.charAt(at);
    }

    private void expect(char symbol) {
        if (peek() != symbol) {
            throw new IllegalArgumentException("Expected " + symbol + " at position " + at);
        }
        at++;
    }

    private void skipSpace() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }
}
