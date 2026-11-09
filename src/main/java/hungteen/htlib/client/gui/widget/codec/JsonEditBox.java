package hungteen.htlib.client.gui.widget.codec;

import hungteen.htlib.common.network.EditorPacketLimits;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** 多行 JSON 草稿。普通粘贴可能过滤字符，「粘贴全文」按原文替换。 */
public final class JsonEditBox extends MultiLineEditBox {
    private final Consumer<String> onEdit;
    private final Consumer<String> onRejected;
    private String draft = "";
    private Object focusKey = this;
    private boolean focusRequested;

    public JsonEditBox(Font font, int x, int y, int width, int height,
                       Consumer<String> onEdit, Consumer<String> onRejected) {
        super(font, x, y, width, height, Component.empty(), Component.literal("JSON"));
        this.onEdit = onEdit;
        this.onRejected = onRejected;
        // 原版的字符上限会截断插入文本。这里检查完整的新值，超限时恢复旧草稿。
        setValueListener(this::edited);
    }

    /** 回显已有草稿（包括暂时不能提交的文本），不触发编辑回调。 */
    public void setDraft(String text) {
        draft = text;
        if (!getValue().equals(text)) {
            super.setValue(text);
        }
    }

    /** 用户主动从剪贴板替换全文，不经过原版按聊天字符过滤的插入逻辑。 */
    public boolean importClipboard() {
        return replaceDraft(Minecraft.getInstance().keyboardHandler.getClipboard());
    }

    boolean replaceDraft(String text) {
        focusRequested = true;
        if (!EditorPacketLimits.fitsJson(text)) {
            rejectSize();
            return false;
        }
        super.setValue(text);
        return true;
    }

    /** 控件因缩放而重建时，用同一表单节点识别原先聚焦的输入框。 */
    public void setFocusKey(Object key) {
        focusKey = key;
    }

    public Object focusKey() {
        return focusKey;
    }

    public boolean takeFocusRequest() {
        boolean requested = focusRequested;
        focusRequested = false;
        return requested;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!isMouseOver(mouseX, mouseY) || !scrollbarVisible()) {
            return false;
        }
        double previous = scrollAmount();
        super.mouseScrolled(mouseX, mouseY, delta);
        return previous != scrollAmount();
    }

    private void edited(String text) {
        if (text.equals(draft)) {
            return;
        }
        // 程序回填的既有文本可能已超限，仍允许删除内容逐步缩短草稿。
        if (!EditorPacketLimits.fitsJson(text)) {
            if (text.length() > draft.length()
                || text.getBytes(StandardCharsets.UTF_8).length >= draft.getBytes(StandardCharsets.UTF_8).length) {
                setDraft(draft);
                rejectSize();
                return;
            }
        }
        draft = text;
        onEdit.accept(text);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible || !active || !isFocused()) {
            return false;
        }
        if (Screen.isPaste(keyCode)) {
            String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (!EditorPacketLimits.fitsJson(clipboard)) {
                rejectSize();
                return true;
            }
            if (!SharedConstants.filterText(clipboard, true).equals(clipboard)) {
                onRejected.accept(I18n.get("htlib.screen.json_paste_filtered"));
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void rejectSize() {
        onRejected.accept(I18n.get("htlib.screen.json_too_large", EditorPacketLimits.MAX_JSON_BYTES));
    }
}
