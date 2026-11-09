package hungteen.htlib.client.gui.widget.codec;

import hungteen.htlib.common.network.EditorPacketLimits;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonEditBoxTest {
    private final List<String> edits = new ArrayList<>();
    private final List<String> rejected = new ArrayList<>();
    // 测试走原版多行编辑逻辑，只以固定字宽替代需要显卡的字体资源。
    private final Font font = new Font(id -> null, false) {
        private final StringSplitter splitter = new StringSplitter((character, style) -> 1);

        @Override
        public StringSplitter getSplitter() {
            return splitter;
        }
    };
    private final JsonEditBox box = new JsonEditBox(font, 0, 0, 200, 80, edits::add, rejected::add);

    @BeforeAll
    static void initializeTranslations() throws ReflectiveOperationException {
        var setLanguage = I18n.class.getDeclaredMethod("setLanguage", Language.class);
        setLanguage.setAccessible(true);
        setLanguage.invoke(null, Language.getInstance());
    }

    @Test
    void refreshIsSilentAndFullReplacementKeepsCharactersFilteredByVanillaPaste() {
        box.setDraft("{");
        box.setFocused(true);
        assertTrue(box.charTyped('}', 0));
        assertEquals(List.of("{}"), edits);
        String raw = "{\r\n\t\"text\":\"§a原文\"\n}";
        assertFalse(box.takeFocusRequest());
        assertTrue(box.replaceDraft(raw));
        assertTrue(box.takeFocusRequest());
        assertFalse(box.takeFocusRequest());
        assertEquals(raw, box.getValue());
        box.setDraft(raw);
        assertEquals(List.of("{}", raw), edits);
        assertTrue(rejected.isEmpty());
    }

    @Test
    void oversizedReplacementOrInsertionDoesNotTruncateOrDirtyTheDraft() {
        box.setDraft("keep me");
        assertFalse(box.replaceDraft("中".repeat(EditorPacketLimits.MAX_JSON_BYTES / 3 + 1)));
        assertTrue(box.takeFocusRequest());
        assertEquals("keep me", box.getValue());
        String full = "x".repeat(EditorPacketLimits.MAX_JSON_BYTES);
        box.setDraft(full);
        box.setFocused(true);
        box.charTyped('y', 0);
        assertEquals(full, box.getValue());
        assertTrue(edits.isEmpty());
        assertEquals(2, rejected.size());
    }
}
