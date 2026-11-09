package hungteen.htlib.client.gui.screen.codec.node;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import hungteen.htlib.client.gui.screen.codec.EditorHost;
import hungteen.htlib.client.gui.widget.codec.EditorWidget;
import hungteen.htlib.client.gui.widget.codec.RecordWidget;
import hungteen.htlib.common.codec.parse.SchemaKeys;

import java.util.ArrayList;
import java.util.List;

/**
 * 记录/对象节点：字段集合，逐字段递归编辑。
 * @author PangTeen
 * @program HTLib
 * @create 2026/9/4 22:50
 */
public final class RecordNode extends EditorFormNode {

    private final List<EditorFormNode> recordChildren = new ArrayList<>();

    RecordNode(JsonObject schema, String label, JsonElement value) {
        super(schema, label, value);
    }

    @Override
    protected void init() {
        if (!schema.has(SchemaKeys.FIELDS)) {
            return;
        }
        for (JsonElement f : schema.getAsJsonArray(SchemaKeys.FIELDS)) {
            JsonObject field = f.getAsJsonObject();
            String name = field.get(SchemaKeys.NAME).getAsString();
            JsonObject fieldSchema = field.getAsJsonObject(SchemaKeys.SCHEMA);
            // 兼容旧格式：字段对象层的 default / required 合并进字段 schema，子节点才能读到
            boolean needMerge = (!fieldSchema.has(SchemaKeys.DEFAULT) && field.has(SchemaKeys.DEFAULT))
                || (!fieldSchema.has(SchemaKeys.REQUIRED) && field.has(SchemaKeys.REQUIRED));
            if (needMerge) {
                // 只复制字段自身的属性，子结构仍由多个字段共享。
                JsonObject merged = new JsonObject();
                fieldSchema.entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue()));
                fieldSchema = merged;
                if (!fieldSchema.has(SchemaKeys.REQUIRED) && field.has(SchemaKeys.REQUIRED)) {
                    fieldSchema.addProperty(SchemaKeys.REQUIRED, field.get(SchemaKeys.REQUIRED).getAsBoolean());
                }
                if (!fieldSchema.has(SchemaKeys.DEFAULT) && field.has(SchemaKeys.DEFAULT)) {
                    fieldSchema.add(SchemaKeys.DEFAULT, field.get(SchemaKeys.DEFAULT));
                }
            }
            recordChildren.add(child(fieldSchema, name, imported && original == null ? null : fieldValue(name)));
        }
    }

    @Override
    public int height() {
        int h = 0;
        for (EditorFormNode c : recordChildren) {
            h += c.height();
        }
        return h;
    }

    @Override
    protected EditorWidget createWidget(EditorHost host) {
        return new RecordWidget(host, this);
    }

    public List<EditorFormNode> children() {
        return recordChildren;
    }

    @Override
    protected List<EditorFormNode> validationChildren() {
        return recordChildren;
    }

    @Override
    protected JsonElement collectValue() {
        JsonObject obj = new JsonObject();
        if (original != null && original.isJsonObject()) {
            original.getAsJsonObject().entrySet().forEach(entry -> obj.add(entry.getKey(), entry.getValue()));
        }
        for (EditorFormNode child : recordChildren) {
            if (imported && !child.edited) {
                continue;
            }
            if (child.isOptionalAndAbsent()) {
                obj.remove(child.label());
                continue;
            }
            JsonElement e = child.collect();
            if (!e.isJsonNull() || child.edited) {
                obj.add(child.label(), e);
            }
        }
        return obj;
    }

    @Override
    protected void reload() {
        for (int i = 0; i < recordChildren.size(); i++) {
            EditorFormNode node = recordChildren.get(i);
            JsonElement input = original == null ? null : fieldValue(node.label());
            if (needsRaw(node.schema, input) == (node instanceof RawJsonNode)) {
                node.load(input);
            } else {
                recordChildren.set(i, child(node.schema, node.label(), input));
            }
        }
    }
}
