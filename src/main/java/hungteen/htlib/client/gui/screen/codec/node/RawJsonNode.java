package hungteen.htlib.client.gui.screen.codec.node;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import hungteen.htlib.client.gui.screen.codec.EditorHost;
import hungteen.htlib.client.gui.screen.codec.EditorJson;
import hungteen.htlib.client.gui.widget.codec.EditorWidget;
import hungteen.htlib.client.gui.widget.codec.RawJsonWidget;

/** 表单无法表示的值直接编辑为 JSON，解析失败时保留文本草稿。 */
public final class RawJsonNode extends EditorFormNode {

    private String rawText;

    RawJsonNode(JsonObject schema, String label, JsonElement value) {
        super(schema, label, value);
    }

    @Override
    protected void init() {
        rawText = value.toString();
        invalid = false;
        errorMessage = "";
    }

    public String rawText() {
        return rawText;
    }

    public void setRawText(String text) {
        if (text.equals(rawText) && (edited || original != null)) {
            return;
        }
        rawText = text;
        try {
            value = EditorJson.parseValue(text);
            invalid = false;
            errorMessage = "";
        } catch (RuntimeException e) {
            invalid = true;
            errorMessage = e.getMessage();
        }
        markChanged();
    }

    @Override
    public void setValueText(String text) {
        setRawText(text);
    }

    @Override
    public void setValue(JsonElement value) {
        setRawText(value == null ? "null" : value.toString());
    }

    @Override
    public String valueText() {
        return rawText;
    }

    @Override
    protected JsonElement collectValue() {
        if (invalid) {
            throw new IllegalStateException(errorMessage);
        }
        return value;
    }

    @Override
    protected void reload() {
        init();
    }

    @Override
    public int height() {
        return RawJsonWidget.HEIGHT;
    }

    @Override
    protected EditorWidget createWidget(EditorHost host) {
        return new RawJsonWidget(host, this);
    }
}
