package site.vinoff.market.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parser that reads what the website sends.
 *
 * <p>Two kinds of test here. The first kind is the escaping, because the thing this replaced got it wrong in a way
 * nobody would notice until a player wrote a note with a line break in it. The second kind is refusal: a body that
 * does not say what it claims to say has to be a refusal rather than a half-read request, since the request on the
 * other side of it moves items out of somebody's chest.
 */
class JsonReaderTest {

    @Test
    @DisplayName("an escaped newline is a newline, which is exactly what the old reader got wrong")
    void escapes() {
        Map<String, Object> read = JsonReader.readObject("{\"note\":\"a\\nb\\ttab \\\"quoted\\\" back\\\\slash\"}");

        assertEquals("a\nb\ttab \"quoted\" back\\slash", read.get("note"));
    }

    @Test
    @DisplayName("a \\u escape becomes the character it names, including one written as a surrogate pair")
    void unicodeEscapes() {
        Map<String, Object> read = JsonReader.readObject("{\"a\":\"\\u0041\",\"cyrillic\":\"\\u0414\",\"pair\":\"\\ud83d\\ude00\"}");

        assertEquals("A", read.get("a"));
        assertEquals("Д", read.get("cyrillic"));
        assertEquals(new String(Character.toChars(0x1F600)), read.get("pair"), "the two halves make one character");
    }

    @Test
    @DisplayName("a list of objects comes back as a list of objects")
    void listOfObjects() {
        Map<String, Object> read = JsonReader.readObject(
                "{\"take\":[{\"slot\":0,\"sha256\":\"ab\",\"amount\":16},{\"slot\":4,\"sha256\":\"cd\",\"amount\":1}]}");

        List<Map<String, Object>> take = JsonReader.objectList(read, "take");
        assertEquals(2, take.size());
        assertEquals(0, JsonReader.requireInt(take.get(0), "slot"));
        assertEquals(16, JsonReader.requireInt(take.get(0), "amount"));
        assertEquals("cd", JsonReader.requireString(take.get(1), "sha256"));
    }

    @Test
    @DisplayName("an absent list is empty, and an absent string is null")
    void absentFields() {
        Map<String, Object> read = JsonReader.readObject("{\"type\":\"GIVEAWAY\"}");

        assertEquals(List.of(), JsonReader.objectList(read, "wanted"));
        assertNull(JsonReader.optionalString(read, "note"));
        assertEquals("GIVEAWAY", JsonReader.requireString(read, "type"));
    }

    @Test
    @DisplayName("an empty string is the same as nothing said, and is refused where something must be said")
    void blankStrings() {
        Map<String, Object> read = JsonReader.readObject("{\"note\":\"   \",\"type\":\"\"}");

        assertNull(JsonReader.optionalString(read, "note"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.requireString(read, "type"));
    }

    @Test
    @DisplayName("whitespace anywhere, nested objects, booleans and null all read")
    void shapes() {
        Map<String, Object> read = JsonReader.readObject(
                "{\n  \"a\" : { \"b\" : [ 1 , 2.5 , true , false , null ] } ,\n  \"c\" : -7\n}");

        Map<?, ?> inner = (Map<?, ?>) read.get("a");
        List<?> values = (List<?>) inner.get("b");
        assertEquals(5, values.size());
        assertEquals(1L, values.get(0));
        assertEquals(2.5d, values.get(1));
        assertEquals(Boolean.TRUE, values.get(2));
        assertEquals(Boolean.FALSE, values.get(3));
        assertNull(values.get(4));
        assertEquals(-7L, read.get("c"));
    }

    @Test
    @DisplayName("a number where a whole number belongs is refused rather than rounded")
    void notAWholeNumber() {
        Map<String, Object> read = JsonReader.readObject("{\"amount\":1.5,\"other\":\"nine\"}");

        assertThrows(IllegalArgumentException.class, () -> JsonReader.requireInt(read, "amount"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.requireInt(read, "other"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.requireInt(read, "missing"));
    }

    @Test
    @DisplayName("malformed bodies are refused, every one of them")
    void refusals() {
        List<String> broken = List.of(
                "",
                "   ",
                "[1,2]",
                "{\"a\":1} trailing",
                "{\"a\":\"unterminated}",
                "{\"a\" 1}",
                "{a:1}",
                "{\"a\":\\x}",
                "{\"a\":\"\\q\"}",
                "{\"a\":\"\\u00zz\"}",
                "{\"a\":[1,2",
                "{");
        for (String body : broken) {
            assertThrows(IllegalArgumentException.class, () -> JsonReader.readObject(body), "should refuse: " + body);
        }
    }

    @Test
    @DisplayName("a body nested past any honest depth is refused instead of exhausting the stack")
    void tooDeep() {
        StringBuilder deep = new StringBuilder("{\"a\":");
        for (int level = 0; level < 200; level++) {
            deep.append('[');
        }
        assertThrows(IllegalArgumentException.class, () -> JsonReader.readObject(deep.toString()));
    }

    @Test
    @DisplayName("an empty object and an empty list read as empty, not as an error")
    void emptyContainers() {
        Map<String, Object> read = JsonReader.readObject("{\"a\":{},\"b\":[]}");

        assertTrue(((Map<?, ?>) read.get("a")).isEmpty());
        assertTrue(((List<?>) read.get("b")).isEmpty());
    }
}
