package hungteen.htlib.client.gui.screen.codec.node;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LosslessJsonFormTest {

    private static final String UNION = """
        {"type":"UNION","variantKey":"kind","variants":[
          {"name":"a","type":"RECORD","fields":[{"name":"field","schema":{"type":"STRING"}}]},
          {"name":"b","type":"RECORD","fields":[]}]}
        """;

    @BeforeAll
    static void initializeLanguage() throws ReflectiveOperationException {
        var setLanguage = I18n.class.getDeclaredMethod("setLanguage", Language.class);
        setLanguage.setAccessible(true);
        setLanguage.invoke(null, Language.getInstance());
    }

    @Test
    void editingOneListItemPreservesUnknownSiblingsTypesAndOmittedDefaults() {
        JsonObject schema = json("""
            {"type":"RECORD","fields":[
              {"name":"list","schema":{"type":"LIST","element":{"type":"RECORD","fields":[
                {"name":"known","schema":{"type":"STRING"}},
                {"name":"flag","schema":{"type":"BOOLEAN","default":true}}]}}},
              {"name":"nil","schema":{"type":"BOOLEAN"}},
              {"name":"omitted","schema":{"type":"INT"}}]}
            """).getAsJsonObject();
        JsonObject original = json("""
            {"list":[{"known":"old","mod:config":[true,null,{}]},{"other":1}],
             "nil":null,"extra":{"large":9007199254740993123456789}}
            """).getAsJsonObject();
        RecordNode root = (RecordNode) EditorFormNode.create(schema, "", original);
        AtomicInteger edits = new AtomicInteger();
        root.onChanged(edits::incrementAndGet);
        assertEquals(original, root.collect());
        assertFalse(root.hasErrors(), "An omitted numeric placeholder is not input");
        assertEquals(0, edits.get());
        ListSetNode list = (ListSetNode) root.children().get(0);
        ((RecordNode) list.items().get(0)).children().get(0).setValueText("new");
        JsonObject expected = original.deepCopy();
        expected.getAsJsonArray("list").get(0).getAsJsonObject().addProperty("known", "new");
        assertEquals(expected, root.collect());
        assertEquals("9007199254740993123456789", root.collect().getAsJsonObject()
            .getAsJsonObject("extra").get("large").toString());
        assertEquals("old", original.getAsJsonArray("list").get(0).getAsJsonObject().get("known").getAsString());
        assertEquals(1, edits.get());
    }

    @ParameterizedTest
    @CsvSource({"INT,1e1", "LONG,1.0"})
    void editingASiblingPreservesNumericSpellingOutsideIntegerTextInput(String type, String literal) {
        JsonObject schema = json("""
            {"type":"RECORD","fields":[{"name":"count","schema":{"type":"%s"}},
              {"name":"other","schema":{"type":"STRING"}}]}
            """.formatted(type)).getAsJsonObject();
        JsonElement input = json("{\"count\":" + literal + ",\"other\":\"old\"}");
        RecordNode root = (RecordNode) EditorFormNode.create(schema, "", input);
        RecordNode reloaded = (RecordNode) EditorFormNode.root(schema);
        reloaded.load(input);
        for (RecordNode form : new RecordNode[] {root, reloaded}) {
            RawJsonNode raw = assertInstanceOf(RawJsonNode.class, form.children().get(0));
            assertEquals(literal, raw.rawText());
            form.children().get(1).setValueText("new");
            assertFalse(form.hasErrors());
            JsonObject output = form.collect().getAsJsonObject();
            assertEquals(literal, output.get("count").toString());
            assertEquals("new", output.get("other").getAsString());
        }
        EditorFormNode scalar = EditorFormNode.root(json("{\"type\":\"" + type + "\"}").getAsJsonObject());
        scalar.load(json(literal));
        assertFalse(scalar.hasErrors());
        assertEquals(literal, scalar.collect().toString());
    }

    @Test
    void invalidRawMapKeyReportsAnErrorBeforeCollectingItsValue() {
        MapNode map = (MapNode) imported("""
            {"type":"MAP","key":{"type":"UNKNOWN"},"value":{"type":"INT"}}
            """, "{\"old\":1}");
        RawJsonNode key = (RawJsonNode) map.keys().get(0);
        key.setRawText("{");
        assertTrue(map.hasErrors());
        assertEquals("{", key.rawText());
        key.setRawText("\"new\"");
        assertFalse(map.hasErrors());
        assertEquals(json("{\"new\":1}"), map.collect());
    }

    @Test
    void editingAnOmittedRecordAddsOnlyItsEditedField() {
        RecordNode root = (RecordNode) imported("""
            {"type":"RECORD","fields":[{"name":"config","schema":{"type":"RECORD","fields":[
              {"name":"flag","schema":{"type":"BOOLEAN","default":true}},
              {"name":"message","schema":{"type":"STRING"}}]}}]}
            """, "{}");
        ((RecordNode) root.children().get(0)).children().get(1).setValueText("hi");
        assertEquals(json("{\"config\":{\"message\":\"hi\"}}"), root.collect());
    }

    @Test
    void explicitNullCanBeDisabledAndInvalidOptionalRawDraftSurvivesReEnable() {
        RecordNode root = (RecordNode) imported("""
            {"type":"RECORD","fields":[{"name":"opt","schema":{"type":"OPTIONAL","element":{"type":"INT"}}}]}
            """, "{\"opt\":null,\"extra\":false}");
        OptionalNode optional = (OptionalNode) root.children().get(0);
        assertTrue(optional.isPresent());
        assertEquals(json("{\"opt\":null,\"extra\":false}"), root.collect());
        RawJsonNode raw = (RawJsonNode) optional.inner();
        raw.setRawText("{");
        assertTrue(root.hasErrors());
        assertThrows(IllegalStateException.class, root::collect);
        optional.setPresent(false);
        assertFalse(root.hasErrors());
        assertEquals(json("{\"extra\":false}"), root.collect());
        optional.setPresent(true);
        assertEquals("{", raw.rawText());
        assertTrue(root.hasErrors());
        raw.setRawText("null");
        assertFalse(root.hasErrors());
        assertEquals(json("{\"opt\":null,\"extra\":false}"), root.collect());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"type\":\"UNKNOWN\"} | {\"inner\":[true]}",
        "{\"type\":\"EITHER\"} | 42",
        "{\"type\":\"RECORD\"} | [false]",
        "{\"type\":\"LIST\",\"element\":{\"type\":\"INT\"}} | {}",
        "{\"type\":\"ENUM\",\"enumValues\":[{\"serializedName\":\"known\"}]} | \"new-name\"",
        "{\"type\":\"HOLDER_SET\"} | [\"minecraft:stone\"]",
        "{\"type\":\"COMPONENT\"} | {\"text\":\"hello\"}"
    })
    void rawFallbackEditsActualJsonValues(String schema, String input) {
        RawJsonNode raw = assertInstanceOf(RawJsonNode.class, imported(schema, input));
        assertEquals(json(input), raw.collect());
        raw.setRawText("{bad");
        assertTrue(raw.hasErrors());
        assertEquals("{bad", raw.rawText());
        assertThrows(IllegalStateException.class, raw::collect);
        raw.setRawText("[null,{\"x\":false}]");
        assertFalse(raw.hasErrors());
        assertEquals(json("[null,{\"x\":false}]"), raw.collect());
        raw.setRawText("\"text\"");
        assertTrue(raw.collect().getAsJsonPrimitive().isString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"kind\":\"mod:unknown\",\"extra\":1}", "{\"kind\":123}", "{}", "[1]"})
    void unknownDiscriminatorKeepsOriginalAndHasAnIndependentRawDraft(String input) {
        UnionNode root = (UnionNode) imported(UNION, input);
        int rawIndex = root.variantIndex();
        RawJsonNode raw = assertInstanceOf(RawJsonNode.class, root.selectedVariant());
        assertEquals(json(input), root.collect());
        raw.setRawText("[");
        root.selectVariant(1);
        assertEquals(json("{\"kind\":\"b\"}"), root.collect());
        assertFalse(root.hasErrors());
        root.selectVariant(rawIndex);
        assertTrue(root.hasErrors());
        assertEquals("[", raw.rawText());
        raw.setRawText(input);
        assertEquals(json(input), root.collect());
    }

    @Test
    void knownBranchesKeepTheirUnknownFieldsAndANewImportDropsOldDrafts() {
        String a = "{\"kind\":\"a\",\"field\":\"old\",\"mod\":null}";
        UnionNode root = (UnionNode) imported(UNION, a);
        AtomicInteger revisions = new AtomicInteger();
        root.onChanged(revisions::incrementAndGet);
        root.selectVariant(1);
        assertEquals(json("{\"kind\":\"b\"}"), root.collect());
        root.selectVariant(0);
        assertEquals(json(a), root.collect());
        ((RecordNode) root.selectedVariant()).children().get(0).setValueText("new");
        assertEquals(json("{\"kind\":\"a\",\"field\":\"new\",\"mod\":null}"), root.collect());
        root.load(json("{\"kind\":\"b\",\"new-extra\":true}"));
        assertEquals(3, revisions.get(), "Loading another input is not a widget edit");
        root.selectVariant(0);
        assertEquals(json("{\"kind\":\"a\"}"), root.collect());
    }

    @Test
    void confirmingTheSameHolderReferenceOnlyWritesAnOmittedDefaultOnce() {
        RecordNode form = (RecordNode) imported("""
            {"type":"RECORD","fields":[
              {"name":"existing","schema":{"type":"HOLDER"}},
              {"name":"omitted","schema":{"type":"HOLDER","default":"demo:default"}}]}
            """, "{\"existing\":\"demo:existing\"}");
        AtomicInteger edits = new AtomicInteger();
        form.onChanged(edits::incrementAndGet);
        form.children().get(0).setValue(json("\"demo:existing\""));
        assertEquals(0, edits.get());
        assertEquals(json("{\"existing\":\"demo:existing\"}"), form.collect());

        EditorFormNode omitted = form.children().get(1);
        omitted.setValue(json("\"demo:default\""));
        assertEquals(1, edits.get());
        assertEquals(json("{\"existing\":\"demo:existing\",\"omitted\":\"demo:default\"}"), form.collect());
        omitted.setValue(json("\"demo:default\""));
        assertEquals(1, edits.get());
    }

    @Test
    void holderWithoutAnInlineSchemaCanEditAndValidateItsRawDraft() {
        HolderNode holder = (HolderNode) imported("{\"type\":\"HOLDER\"}", "{\"nested\":[1]}");
        AtomicInteger edits = new AtomicInteger();
        holder.onChanged(edits::incrementAndGet);
        RawJsonNode raw = (RawJsonNode) holder.inlineRawNode();
        assertSame(raw, holder.inlineRawNode());
        assertEquals(0, edits.get());
        raw.setRawText("[");
        assertTrue(holder.hasErrors());
        raw.setRawText("{\"nested\":null}");
        assertEquals(json("{\"nested\":null}"), holder.collect());
        assertEquals(2, edits.get());
    }

    @Test
    void holderSwitchesKeepTheReferenceAndInvalidInlineDraftUntilAnotherImport() {
        HolderNode holder = (HolderNode) imported("{\"type\":\"HOLDER\"}", "\"demo:original\"");
        holder.toggleMode();
        RawJsonNode raw = (RawJsonNode) holder.inlineRawNode();
        raw.setRawText("{");
        assertTrue(holder.hasErrors());
        holder.toggleMode();
        assertEquals(json("\"demo:original\""), holder.collect());
        assertFalse(holder.hasErrors());
        holder.setValue(json("\"demo:edited\""));
        holder.toggleMode();
        assertSame(raw, holder.inlineRawNode());
        assertEquals("{", raw.rawText());
        assertTrue(holder.hasErrors());
        raw.setRawText("{\"checked\":null}");
        holder.toggleMode();
        assertEquals(json("\"demo:edited\""), holder.collect());
        holder.toggleMode();
        assertEquals(json("{\"checked\":null}"), holder.collect());

        raw.setRawText("[");
        holder.toggleMode();
        holder.load(json("\"demo:imported\""));
        holder.toggleMode();
        assertEquals(json("{}"), holder.collect());
        assertFalse(holder.hasErrors());
        holder.toggleMode();
        assertEquals(json("\"demo:imported\""), holder.collect());
        holder.load(json("{\"replacement\":42}"));
        assertEquals(json("{\"replacement\":42}"), holder.collect());
        holder.toggleMode();
        assertEquals(json("\"\""), holder.collect());
        holder.toggleMode();
        assertEquals(json("{\"replacement\":42}"), holder.collect());
    }

    @Test
    void lateHolderSchemaBindsTheInlineObjectWhileItsReferenceModeIsActive() {
        String entry = "{\"mod:id\":\"old\"}";
        HolderNode holder = (HolderNode) imported("{\"type\":\"HOLDER\"}", entry);
        holder.toggleMode();
        holder.inlineNode(json("{\"type\":\"RECORD\",\"fields\":[]}").getAsJsonObject());
        holder.toggleMode();
        assertEquals(json(entry), holder.collect());
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void newInlineFormUsesItsDefaultsForFreshAndImportedReferences(boolean earlySchema, boolean importedReference) {
        JsonObject schema = json("""
            {"type":"RECORD","fields":[{"name":"limit","schema":{"type":"INT","default":5}},
              {"name":"required","schema":{"type":"INT","required":true}}]}
            """).getAsJsonObject();
        JsonObject holderSchema = json("{\"type\":\"HOLDER\"}").getAsJsonObject();
        HolderNode holder = (HolderNode) EditorFormNode.create(holderSchema, "",
            importedReference ? json("\"demo:original\"") : null);
        if (earlySchema) {
            holder.inlineNode(schema);
        }
        holder.toggleMode();
        RecordNode form = (RecordNode) holder.inlineNode(schema);
        assertEquals(json("{\"limit\":5}"), holder.collect());
        assertTrue(holder.hasErrors(), "A new form must validate its missing required number");
        form.children().get(1).setValueText("7");
        assertFalse(holder.hasErrors());
        assertEquals(json("{\"limit\":5,\"required\":7}"), holder.collect());

        holder.load(json("{}"));
        assertEquals(json("{}"), holder.collect());
        assertFalse(holder.hasErrors(), "An imported empty object preserves its missing fields");
        holder.load(json("\"demo:replacement\""));
        holder.toggleMode();
        assertEquals(json("{\"limit\":5}"), holder.collect());
        assertTrue(holder.hasErrors(), "Replacing the document discards the previous inline form");
    }

    private static EditorFormNode imported(String schema, String input) {
        return EditorFormNode.create(json(schema).getAsJsonObject(), "", json(input));
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }
}
