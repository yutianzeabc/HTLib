package hungteen.htlib.client.gui.screen.codec.node;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import hungteen.htlib.client.gui.screen.codec.SchemaTooltip;
import hungteen.htlib.client.gui.screen.codec.EditorHost;
import hungteen.htlib.client.gui.widget.codec.EditorWidget;
import hungteen.htlib.client.gui.widget.codec.ListSetWidget;
import hungteen.htlib.common.codec.parse.SchemaKeys;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * 列表/集合节点：头部增删，元素逐项递归编辑。
 * @author PangTeen
 * @program HTLib
 * @create 2026/9/4 22:50
 */
public final class ListSetNode extends EditorFormNode {

    private final List<EditorFormNode> listItems = new ArrayList<>();

    ListSetNode(JsonObject schema, String label, JsonElement value) {
        super(schema, label, value);
    }

    @Override
    protected void init() {
        JsonObject el = elementSchema();
        if (el == null) {
            return;
        }
        if (value.isJsonArray()) {
            for (JsonElement e : value.getAsJsonArray()) {
                listItems.add(child(el, "", e));
            }
        }
    }

    @Override
    public int height() {
        int h = EditorWidget.ROW_HEIGHT;
        for (EditorFormNode item : listItems) {
            h += item.height();
        }
        return h;
    }

    @Override
    protected EditorWidget createWidget(EditorHost host) {
        return new ListSetWidget(host, this);
    }

    public List<EditorFormNode> items() {
        return listItems;
    }

    @Override
    protected List<EditorFormNode> validationChildren() {
        return listItems;
    }

    public void addItem() {
        listItems.add(attach(EditorFormNode.create(elementSchema(), "", null)));
        validateValue();
        markChanged();
    }

    public void removeLastItem() {
        if (!listItems.isEmpty()) {
            listItems.remove(listItems.size() - 1);
            validateValue();
            markChanged();
        }
    }

    @Override
    protected JsonElement collectValue() {
        JsonArray arr = new JsonArray();
        for (EditorFormNode item : listItems) {
            arr.add(item.collect());
        }
        return arr;
    }

    @Override
    protected void reload() {
        listItems.clear();
        init();
    }

    @Override
    protected void validateValue() {
        invalid = false;
        errorMessage = "";
        if (!schema.has(SchemaKeys.CONSTRAINTS)) {
            return;
        }
        for (JsonElement c : schema.getAsJsonArray(SchemaKeys.CONSTRAINTS)) {
            String err = SchemaTooltip.checkConstraint(c.getAsString(), SchemaTooltip.Kind.SIZE,
                "", listItems.size());
            if (err != null) {
                invalid = true;
                errorMessage = I18n.get("htlib.error.constraint", err);
                return;
            }
        }
    }
}
