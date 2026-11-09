package hungteen.htlib.client.gui.screen.codec.node;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import hungteen.htlib.client.gui.screen.codec.EditorHost;
import hungteen.htlib.client.gui.widget.codec.EditorWidget;
import hungteen.htlib.client.gui.widget.codec.HolderWidget;
import hungteen.htlib.client.gui.widget.codec.RegistryEntriesCache;
import hungteen.htlib.common.codec.parse.SchemaKeys;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 注册表引用节点（HOLDER / HOLDER_SET）。
 * @author PangTeen
 * @program HTLib
 * @create 2026/9/4 22:50
 */
public final class HolderNode extends EditorFormNode {

    /** 可补全的注册表条目名（客户端本地枚举）。 */
    private List<String> entries = List.of();
    private EditorFormNode inlineNode;
    private JsonElement referenceValue;
    /** 只有实际载入的对象才是内联表单的输入。切换模式时的空对象只是显示标记。 */
    private JsonObject inlineInput;

    HolderNode(JsonObject schema, String label, JsonElement value) {
        super(schema, label, value);
    }

    @Override
    protected void init() {
        resetInputs();
        if (schema.has(SchemaKeys.REGISTRY)) {
            String name = schema.getAsJsonObject(SchemaKeys.REGISTRY).get(SchemaKeys.REGISTRY).getAsString();
            // 条目列表走共享缓存（同一注册表只枚举一次，本地结果也写回缓存给所有 Holder 下拉复用）
            List<String> cached = RegistryEntriesCache.entries(name);
            if (!cached.isEmpty()) {
                entries = cached;
            } else {
                List<String> scanned = clientRegistryEntries(name);
                entries = scanned;
                if (!scanned.isEmpty()) {
                    RegistryEntriesCache.update(name, scanned);
                }
            }
        }
    }

    @Override
    public int height() {
        return EditorWidget.ROW_HEIGHT;
    }

    @Override
    protected EditorWidget createWidget(EditorHost host) {
        return new HolderWidget(host, this);
    }

    public void setEntries(List<String> entries) {
        this.entries = entries;
    }

    public List<String> entries() {
        return entries;
    }

    /** schema 声明的注册表名（无注册表信息时为 null）。 */
    public String registryName() {
        return schema.has(SchemaKeys.REGISTRY)
            ? schema.getAsJsonObject(SchemaKeys.REGISTRY).get(SchemaKeys.REGISTRY).getAsString() : null;
    }

    /** 界面重建时复用内联草稿，包括还不能收集成 JSON 的重复键。 */
    public EditorFormNode inlineNode(JsonObject entrySchema) {
        if (inlineNode == null) {
            inlineNode = createInlineNode(entrySchema);
        }
        return inlineNode;
    }

    /** 未取得条目的 Schema 时，仍可编辑整个内联值。 */
    public EditorFormNode inlineRawNode() {
        return inlineNode(null);
    }

    private boolean hasActiveInlineNode() {
        return inlineNode != null && value != null && value.isJsonObject();
    }

    @Override
    protected List<EditorFormNode> validationChildren() {
        return hasActiveInlineNode() ? List.of(inlineNode) : List.of();
    }

    @Override
    protected JsonElement collectValue() {
        if (hasActiveInlineNode()) {
            return inlineNode.collect();
        }
        return value == null ? JsonNull.INSTANCE : value;
    }

    @Override
    public void setValue(JsonElement value) {
        if (value != null && value.isJsonObject()) {
            inlineInput = value.getAsJsonObject();
            if (inlineNode != null) {
                inlineNode.load(value);
            }
        } else {
            JsonElement reference = value == null ? JsonNull.INSTANCE : value;
            if ((original != null || edited) && reference.equals(this.value)) {
                return;
            }
            referenceValue = reference;
        }
        super.setValue(value);
    }

    /** 切换时只更换当前值，不重新载入或收集可能还不合法的内联草稿。 */
    public void toggleMode() {
        if (value.isJsonObject()) {
            value = referenceValue == null ? new JsonPrimitive("") : referenceValue;
        } else {
            value = inlineInput == null ? new JsonObject() : inlineInput;
        }
        markChanged();
    }

    @Override
    protected void reload() {
        resetInputs();
        if (inlineNode != null) {
            if (inlineInput != null) {
                inlineNode.load(inlineInput);
            } else {
                // 文档现在只包含引用；以后首次创建的内联值应使用完整的新建表单校验。
                inlineNode.onChanged(null);
                inlineNode = createInlineNode(inlineNode.schema);
            }
        }
    }

    private void resetInputs() {
        inlineInput = value.isJsonObject() ? value.getAsJsonObject() : null;
        referenceValue = value.isJsonObject() ? null : value;
    }

    private EditorFormNode createInlineNode(JsonObject entrySchema) {
        if (inlineInput != null) {
            return child(entrySchema, "", inlineInput);
        }
        // 从引用新建内联值时，原文从空对象开始，已知表单使用自己的默认值与必填校验。
        return needsRaw(entrySchema, null) ? child(entrySchema, "", new JsonObject())
            : attach(EditorFormNode.root(entrySchema));
    }

    /**
     * 客户端本地枚举注册表条目：内置注册表走 {@link BuiltInRegistries}。
     */
    private static List<String> clientRegistryEntries(String registryName) {
        List<String> result = new ArrayList<>();
        ResourceLocation name = ResourceLocation.tryParse(registryName);
        if (name == null) {
            return result;
        }
        Registry<?> builtin = BuiltInRegistries.REGISTRY.get(name);
        if (builtin != null) {
            for (var entry : builtin.entrySet()) {
                result.add(entry.getKey().location().toString());
            }
            return result;
        }
        return result;
    }
}
