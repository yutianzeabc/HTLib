package hungteen.htlib.client.gui.screen.codec;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.internal.LazilyParsedNumber;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;

/** 编辑器原文输入：只接受一个严格 JSON 值，数值保留原精度。 */
public final class EditorJson {

    private static final Gson PRETTY = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private EditorJson() {
    }

    public static String format(JsonElement value) {
        return PRETTY.toJson(value);
    }

    public static JsonElement parse(String text) {
        JsonElement value = parseValue(text);
        if (value.isJsonNull()) {
            throw new IllegalArgumentException("Expected a non-null JSON value");
        }
        return value;
    }

    /** 子字段可以显式使用 null，但空输入不能代替 JSON null。 */
    public static JsonElement parseValue(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Expected one JSON value");
        }
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement value = readValue(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Expected one JSON value");
            }
            return value;
        } catch (IOException | JsonParseException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    /** 拒绝会被对象模型静默合并的同名键，数值保留词法精度。 */
    private static JsonElement readValue(JsonReader reader, int depth) throws IOException {
        if (depth > 256) {
            throw new IllegalArgumentException("JSON is nested too deeply");
        }
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) {
                        throw new IllegalArgumentException("Duplicate JSON key: " + name + " at " + reader.getPath());
                    }
                    object.add(name, readValue(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) {
                    array.add(readValue(reader, depth + 1));
                }
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new LazilyParsedNumber(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IllegalArgumentException("Expected one JSON value at " + reader.getPath());
        };
    }
}
