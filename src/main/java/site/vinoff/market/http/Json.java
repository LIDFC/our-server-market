package site.vinoff.market.http;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny JSON writer. The API answers with a handful of shapes, so a dependency for this would cost more than it
 * saves, and everything written here is escaped in one place.
 */
public final class Json {

    private final StringBuilder text = new StringBuilder();
    private final List<Boolean> firstInScope = new ArrayList<>();

    public static Json object() {
        Json json = new Json();
        json.text.append('{');
        json.firstInScope.add(true);
        return json;
    }

    public static Json array() {
        Json json = new Json();
        json.text.append('[');
        json.firstInScope.add(true);
        return json;
    }

    private void separate() {
        int depth = firstInScope.size() - 1;
        if (firstInScope.get(depth)) {
            firstInScope.set(depth, false);
        } else {
            text.append(',');
        }
    }

    public Json field(String name, String value) {
        separate();
        text.append(quote(name)).append(':');
        if (value == null) {
            text.append("null");
        } else {
            text.append(quote(value));
        }
        return this;
    }

    public Json field(String name, long value) {
        separate();
        text.append(quote(name)).append(':').append(value);
        return this;
    }

    public Json field(String name, boolean value) {
        separate();
        text.append(quote(name)).append(':').append(value);
        return this;
    }

    public Json field(String name, Json value) {
        separate();
        text.append(quote(name)).append(':').append(value.done());
        return this;
    }

    public Json add(Json value) {
        separate();
        text.append(value.done());
        return this;
    }

    public Json add(String value) {
        separate();
        text.append(quote(value));
        return this;
    }

    /** Closes the object or array and returns the text. */
    public String done() {
        if (text.length() > 0 && text.charAt(0) == '{') {
            text.append('}');
        } else {
            text.append(']');
        }
        firstInScope.clear();
        firstInScope.add(false);
        return text.toString();
    }

    public static String error(String code, String message) {
        return Json.object().field("error", Json.object().field("code", code).field("message", message)).done();
    }

    static String quote(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2);
        quoted.append('"');
        for (int index = 0; index < value.length(); index++) {
            char symbol = value.charAt(index);
            switch (symbol) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (symbol < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) symbol));
                    } else {
                        quoted.append(symbol);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    /** Reads one string field out of a small flat JSON object, without pulling in a parser. */
    public static String readString(String body, String field) {
        String needle = quote(field) + ":";
        int start = body.indexOf(needle);
        if (start < 0) {
            return null;
        }
        int cursor = start + needle.length();
        while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= body.length() || body.charAt(cursor) != '"') {
            return null;
        }
        StringBuilder value = new StringBuilder();
        for (int index = cursor + 1; index < body.length(); index++) {
            char symbol = body.charAt(index);
            if (symbol == '\\' && index + 1 < body.length()) {
                value.append(body.charAt(++index));
                continue;
            }
            if (symbol == '"') {
                return value.toString();
            }
            value.append(symbol);
        }
        return null;
    }
}
