package hungteen.htlib.client.gui.widget.codec;

import hungteen.htlib.client.gui.screen.codec.EditorHost;
import hungteen.htlib.client.gui.screen.codec.node.RawJsonNode;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.language.I18n;

/** 表单无法表示的值直接按 JSON 编辑，包括对象、数组与 null。 */
public final class RawJsonWidget extends EditorWidget {
    public static final int HEIGHT = ROW_HEIGHT * 4;

    private final RawJsonNode node;
    private JsonEditBox box;
    private Button paste;

    public RawJsonWidget(EditorHost host, RawJsonNode node) {
        super(host, node);
        this.node = node;
    }

    @Override
    protected void create() {
        String caption = I18n.get("htlib.screen.import_clipboard");
        paste = createButton(caption, host.font().width(caption) + 8, b -> box.importClipboard());
        box = new JsonEditBox(host.font(), x, y + ROW_HEIGHT, Math.max(24, rightEdge() - x), HEIGHT - ROW_HEIGHT,
            node::setRawText, host::showInputError);
        box.setFocusKey(node);
        attachWidget(box);
    }

    @Override
    protected int doLayout() {
        place(paste, Math.max(x, rightEdge() - TOGGLE_WIDTH - TOGGLE_GAP - paste.getWidth()), y);
        place(box, x, y + ROW_HEIGHT);
        return HEIGHT;
    }

    @Override
    protected void doRender(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        String label = displayLabel();
        drawLabel(graphics, font, label.isEmpty() ? "JSON" : label, labelColor());
    }

    @Override
    public void refreshFromNode() {
        if (box != null) {
            box.setDraft(node.rawText());
        }
    }
}
