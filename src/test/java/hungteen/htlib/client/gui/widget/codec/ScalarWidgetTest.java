package hungteen.htlib.client.gui.widget.codec;

import com.google.gson.JsonParser;
import hungteen.htlib.client.gui.screen.codec.CodecEditorScreen;
import hungteen.htlib.client.gui.screen.codec.node.EditorFormNode;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScalarWidgetTest {
    @Test
    void importedScalarsAreVisibleAndMovingTheCaretIsNotAnEdit() {
        var schema = JsonParser.parseString("""
            {"type":"RECORD","fields":[
              {"name":"message_id","schema":{"type":"STRING"}},
              {"name":"exhaustion","schema":{"type":"FLOAT"}}]}
            """).getAsJsonObject();
        var value = JsonParser.parseString("""
            {"message_id":"json_drafts_test","exhaustion":0.25,"extra":{"items":[null,9007199254740993]}}
            """);
        var root = EditorFormNode.create(schema, "", value);
        var edits = new AtomicInteger();
        root.onChanged(edits::incrementAndGet);
        var screen = new CodecEditorScreen(List.of()) {
            @Override
            public Font font() {
                return FIXED_FONT;
            }
        };
        var form = root.widget(screen);
        form.layout(0, screen.topOffset(), 320);
        var message = (EditBox) screen.children().get(0);
        var exhaustion = (EditBox) screen.children().get(1);
        assertEquals("json_drafts_test", message.getValue());
        assertEquals("0.25", exhaustion.getValue());

        // 原版点击内边缘会把光标移到第一个可见字符，能检测值存在但画面留白的回归。
        message.onClick(message.getX() + 4, message.getY());
        assertTrue(message.getCursorPosition() < message.getValue().length());
        exhaustion.onClick(exhaustion.getX() + 4, exhaustion.getY());
        assertEquals(0, exhaustion.getCursorPosition());
        form.refreshFromNode();
        assertEquals(0, exhaustion.getCursorPosition());
        assertEquals(0, edits.get());
        assertEquals(value, root.collect());

        exhaustion.setValue("0.5");
        assertEquals(1, edits.get());
        assertEquals("0.5", root.collect().getAsJsonObject().get("exhaustion").getAsString());
    }

    private static final Font FIXED_FONT = new Font(id -> null, false) {
        private final StringSplitter fixed = new StringSplitter((character, style) -> 6);

        @Override
        public int width(String text) {
            return (int) fixed.stringWidth(text);
        }

        @Override
        public String plainSubstrByWidth(String text, int width) {
            return fixed.plainHeadByWidth(text, width, Style.EMPTY);
        }

        @Override
        public String plainSubstrByWidth(String text, int width, boolean fromEnd) {
            return fromEnd ? fixed.plainTailByWidth(text, width, Style.EMPTY) : plainSubstrByWidth(text, width);
        }
    };
}
