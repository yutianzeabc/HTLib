package hungteen.htlib.client.gui.screen.codec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class EditorJsonTest {

    @Test
    void retainsNestedNullUnicodeAndExactNumbers() {
        var object = EditorJson.parse("{\"extra\":[null,9007199254740993,\"§a森林\"],\"fraction\":0.123456789012345678901}")
            .getAsJsonObject();
        assertTrue(object.getAsJsonArray("extra").get(0).isJsonNull());
        assertEquals("9007199254740993", object.getAsJsonArray("extra").get(1).toString());
        assertEquals("§a森林", object.getAsJsonArray("extra").get(2).getAsString());
        assertEquals("0.123456789012345678901", object.get("fraction").toString());
        object.add("explicit_null", com.google.gson.JsonNull.INSTANCE);
        var formatted = EditorJson.parse(EditorJson.format(object)).getAsJsonObject();
        assertTrue(formatted.has("explicit_null"));
        assertTrue(formatted.get("explicit_null").isJsonNull());
        assertEquals("9007199254740993", formatted.getAsJsonArray("extra").get(1).toString());
        assertTrue(EditorJson.parseValue("null").isJsonNull());
        assertThrows(IllegalArgumentException.class, () -> EditorJson.parse("null"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{a:1}", "{'a':1}", "{\"a\":1,}", "// comment\n{}", "{} []", "[NaN]",
        "{\"x\":1,\"x\":2}", "{\"child\":{\"x\":null,\"x\":2}}"})
    void rejectsIncompleteOrLenientJsonWithoutReplacingTheDraft(String text) {
        assertThrows(IllegalArgumentException.class, () -> EditorJson.parseValue(text));
    }
}
