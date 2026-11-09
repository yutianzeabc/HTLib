package hungteen.htlib.client.gui.screen.codec.node;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import hungteen.htlib.client.gui.screen.codec.ViewerStyle;
import hungteen.htlib.client.gui.screen.codec.EditorHost;
import hungteen.htlib.client.gui.widget.codec.EditorWidget;
import hungteen.htlib.client.gui.widget.codec.UnionWidget;
import hungteen.htlib.common.codec.parse.SchemaKeys;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 联合（dispatch）节点：类型下拉 + 当前分支的字段。
 * @author PangTeen
 * @program HTLib
 * @create 2026/9/4 22:50
 */
public final class UnionNode extends EditorFormNode {

    private static final String DEFAULT_VARIANT_KEY = "type";

    private List<String> unionNames = List.of();
    private JsonArray variantSchemas = new JsonArray();
    /** 只创建切换到的分支，并保留各自的草稿。 */
    private final Map<Integer, EditorFormNode> unionVariants = new HashMap<>();
    private int unionIndex = 0;

    UnionNode(JsonObject schema, String label, JsonElement value) {
        super(schema, label, value);
    }

    @Override
    protected void init() {
        if (schema.has(SchemaKeys.VARIANTS)) {
            variantSchemas = schema.getAsJsonArray(SchemaKeys.VARIANTS);
        }
        // 只读取当前层的名称。用整个 schema 作缓存键会递归计算共享子结构的哈希。
        List<String> names = new ArrayList<>();
        for (JsonElement variant : variantSchemas) {
            JsonObject entry = variant.getAsJsonObject();
            names.add(entry.has(SchemaKeys.NAME) ? entry.get(SchemaKeys.NAME).getAsString() : "分支" + names.size());
        }
        unionIndex = original == null ? firstVariant() : matchUnionVariant(original, names);
        if (unionIndex < 0) {
            // 未识别的分派键保留整段 JSON，只有用户选择其它分支才替换它。
            unionIndex = names.size();
            String rawName = I18n.get("htlib.screen.raw_json");
            while (names.contains(rawName)) {
                rawName += "*";
            }
            names.add(rawName);
            unionVariants.put(unionIndex, child(null, "", original));
        }
        unionNames = List.copyOf(names);
        if (original != null && unionIndex < variantSchemas.size()) {
            unionVariants.put(unionIndex, createVariant(unionIndex, original.getAsJsonObject()));
        }
    }

    @Override
    public int height() {
        return EditorWidget.ROW_HEIGHT + selectedVariant().height();
    }

    @Override
    protected EditorWidget createWidget(EditorHost host) {
        return new UnionWidget(host, this);
    }

    public List<String> unionNames() {
        return unionNames;
    }

    public int variantIndex() {
        return unionIndex;
    }

    public String currentVariantName() {
        return unionIndex >= 0 && unionIndex < unionNames.size() ? unionNames.get(unionIndex) : "";
    }

    public String discriminatorKey() {
        return schema.has(SchemaKeys.VARIANT_KEY)
            ? schema.get(SchemaKeys.VARIANT_KEY).getAsString() : DEFAULT_VARIANT_KEY;
    }

    public EditorFormNode selectedVariant() {
        return unionVariants.computeIfAbsent(unionIndex, index -> createVariant(index, null));
    }

    private EditorFormNode createVariant(int index, JsonObject input) {
        JsonObject variant = variantSchemas.get(index).getAsJsonObject();
        switch (typeOf(variant)) {
            case RECORD, MAP, UNION -> {
                if (input == null) {
                    return imported && original == null ? child(variant, "", null) : attach(EditorFormNode.root(variant));
                }
                if (!needsRaw(variant, input)) {
                    // 分派键属于外层。未知字段保存在当前分支，切换时不会带到其它分支。
                    JsonObject body = new JsonObject();
                    input.entrySet().forEach(entry -> {
                        if (!entry.getKey().equals(discriminatorKey())) {
                            body.add(entry.getKey(), entry.getValue());
                        }
                    });
                    return child(variant, "", body);
                }
            }
            default -> { }
        }
        // 此分支没有可投影的对象结构，保留包含分派键的完整值。
        if (input == null) {
            input = new JsonObject();
            input.addProperty(discriminatorKey(), unionNames.get(index));
        }
        return child(null, "", input);
    }

    /** 切换到指定变体，保留之前编辑的分支草稿。 */
    public void selectVariant(int idx) {
        if (idx < 0 || idx >= unionNames.size() || idx == unionIndex) {
            return;
        }
        unionIndex = idx;
        markChanged();
    }

    @Override
    protected List<EditorFormNode> validationChildren() {
        return List.of(selectedVariant());
    }

    @Override
    protected JsonElement collectValue() {
        EditorFormNode selected = selectedVariant();
        JsonElement inner = selected.collect();
        if (selected instanceof RawJsonNode) {
            return inner;
        }
        // 用 {分派键: 变体名, ...变体字段} 组装，符合 dispatch codec 的 JSON 结构。
        JsonObject obj = new JsonObject();
        if (inner.isJsonObject()) {
            for (var e : inner.getAsJsonObject().entrySet()) {
                obj.add(e.getKey(), e.getValue());
            }
        }
        obj.addProperty(discriminatorKey(), currentVariantName());
        return obj;
    }

    @Override
    protected void reload() {
        unionVariants.clear();
        init();
    }

    @Override
    protected void appendTypeInfo(List<Component> lines) {
        if (schema.has(SchemaKeys.VARIANT_KEY)) {
            lines.add(line(I18n.get("htlib.tooltip.variant_key", schema.get(SchemaKeys.VARIANT_KEY).getAsString()),
                ViewerStyle.COLOR_TYPE));
        }
        if (!unionNames.isEmpty()) {
            lines.add(line(I18n.get("htlib.tooltip.types", unionNames.size(), String.join(", ", unionNames)),
                ViewerStyle.COLOR_ENUM));
        }
    }

    private int matchUnionVariant(JsonElement value, List<String> names) {
        if (value.isJsonObject()) {
            JsonElement typeVal = value.getAsJsonObject().get(discriminatorKey());
            if (typeVal != null && typeVal.isJsonPrimitive() && typeVal.getAsJsonPrimitive().isString()) {
                return names.indexOf(typeVal.getAsString());
            }
        }
        return -1;
    }

    private int firstVariant() {
        for (int i = 0; i < variantSchemas.size(); i++) {
            switch (typeOf(variantSchemas.get(i).getAsJsonObject())) {
                case RECORD, MAP, UNION, EITHER -> { return i; }
                default -> { }
            }
        }
        return variantSchemas.isEmpty() ? -1 : 0;
    }
}
