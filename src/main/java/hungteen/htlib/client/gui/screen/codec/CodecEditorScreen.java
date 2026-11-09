package hungteen.htlib.client.gui.screen.codec;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hungteen.htlib.client.gui.screen.codec.node.EditorFormNode;
import hungteen.htlib.client.gui.widget.codec.EditorResultCache;
import hungteen.htlib.client.gui.widget.codec.EditorWidget;
import hungteen.htlib.client.gui.widget.codec.EntrySchemaCache;
import hungteen.htlib.client.gui.widget.codec.JsonEditBox;
import hungteen.htlib.client.gui.widget.codec.TypeSelector;
import hungteen.htlib.common.codec.parse.SchemaReferences;
import hungteen.htlib.common.network.EditorPacketLimits;
import hungteen.htlib.common.network.EditorResultPacket;
import hungteen.htlib.common.network.NetworkHandler;
import hungteen.htlib.common.network.SaveDataPacket;
import hungteen.htlib.common.network.ValidateDataPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * Codec 编辑器界面。
 *
 * <p>顶部工具条：数据包类型下拉框（支持搜索）、表单/JSON 模式切换、保存路径与文件名、保存按钮。
 * 主体区域：表单或 JSON 文本视图。表单支持滚轮滚动与右键拖拽平移。</p>
 *
 * <p>控件由 {@link EditorWidget} 树持有：滚动/平移只触发 {@link #layoutForm()} 重摆，
 * 结构变化（增删行/折叠/切换变体）由控件回调 {@link EditorHost#relayout()} 增量更新； 仅在 schema 变更、模式切换、窗口 resize 时全量 {@link #rebuild()}。</p>
 *
 * @author PangTeen
 * @program HTLib
 * @create 2026/9/4 22:50
 **/
public class CodecEditorScreen extends CodecScreen implements EditorHost {

    private static final int TOP_OFFSET = 20;
    /** 顶部类型输入框宽度（编辑器工具条更紧凑）。 */
    private static final int TYPE_DROPDOWN_WIDTH = 120;
    private static final int TOOLBAR_GAP = 6;
    private static final int MODE_BUTTON_WIDTH = 50;

    private boolean jsonMode = false;
    private JsonObject schema;
    private EditorFormNode formRoot;
    private String datapackName = "";
    private String saveRegistryName = "";
    private hungteen.htlib.client.gui.widget.codec.DropdownButton saveDropdown;
    private Button modeButton;
    private final EditorDocument document = new EditorDocument();
    private JsonEditBox jsonBox;
    private String jsonDraft = "{}";
    private boolean jsonEdited;
    private boolean formChanged;
    private boolean formChangedSinceJson = true;
    private boolean confirming;
    private boolean closed;
    private boolean restoreInputFocus;
    private JsonEditBox preferredInput;
    private int formScroll = 0;
    private int panX = 0;

    /* 右键拖拽平移 */
    private boolean panning = false;
    private double panLastX, panLastY;

    /* 导入 JSON 模式 */
    private boolean importMode = false;
    private JsonEditBox importBox;
    private String importDraft = "";

    public CodecEditorScreen(List<ResourceLocation> registryNames) {
        super(registryNames);
    }

    /**
     * 打开编辑器界面（由 OpenCodecPacket 调用）。
     */
    public static void open(List<ResourceLocation> registryNames) {
        Minecraft.getInstance().setScreen(new CodecEditorScreen(registryNames));
    }

    /** 顶部类型补全输入框（候选为全部注册表名）。 */
    @Override
    protected TypeSelector createTypeSelector() {
        List<String> names = registryNames.stream().map(ResourceLocation::toString).toList();
        // 为两个名称输入框预留 110 像素，窄窗口优先缩短类型框。
        int available = width - 2 * ViewerStyle.LEFT_PADDING - 4 * TOOLBAR_GAP - MODE_BUTTON_WIDTH - actionsWidth();
        int typeWidth = Math.min(TYPE_DROPDOWN_WIDTH, Math.max(60, available - 110));
        return new TypeSelector(this, this::addRenderableWidget, ViewerStyle.LEFT_PADDING, 4, typeWidth, 13,
            TOP_OFFSET, names, this::select);
    }

    private int actionsWidth() {
        return Math.max(40, this.font.width(I18n.get("htlib.screen.more")) + 8);
    }

    @Override
    protected void onInit() {
        focusJsonInput();
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        Object focusKey = getFocused() instanceof JsonEditBox box ? box.focusKey() : null;
        super.resize(minecraft, width, height);
        restoreFormInput(focusKey);
    }

    private void restoreFormInput(Object focusKey) {
        if (focusKey != null && !jsonMode && !importMode) {
            for (var child : children()) {
                if (child instanceof JsonEditBox box && box.focusKey() == focusKey) {
                    preferredInput = box;
                    focusJsonInput();
                    break;
                }
            }
        }
    }

    private void focusJsonInput() {
        if (importMode && importBox != null) {
            preferredInput = importBox;
        } else if (jsonMode && jsonBox != null) {
            preferredInput = jsonBox;
        }
        if (preferredInput != null && children().contains(preferredInput)) {
            setFocused(preferredInput);
            restoreInputFocus = true;
        }
    }

    /** 原版按钮回调结束后会再次设置焦点，重建出的输入框须在事件结束时接回焦点。 */
    private boolean finishInputEvent(boolean handled) {
        for (var child : children()) {
            if (child instanceof JsonEditBox box && box.takeFocusRequest()) {
                preferredInput = box;
                restoreInputFocus = true;
            }
        }
        if (restoreInputFocus && Minecraft.getInstance().screen == this) {
            focusJsonInput();
        }
        restoreInputFocus = false;
        return handled;
    }

    /**
     * 全量重建：销毁旧控件树 → 清空界面 → 工具条 → 布局表单。
     */
    @Override
    public void rebuild() {
        Object focusKey = getFocused() instanceof JsonEditBox box ? box.focusKey() : null;
        preferredInput = null;
        if (jsonBox != null) {
            jsonDraft = jsonBox.getValue();
        }
        if (importBox != null) {
            importDraft = importBox.getValue();
        }
        jsonBox = null;
        importBox = null;
        if (formRoot != null && formRoot.hasWidget()) {
            formRoot.widget(this).dispose();
        }
        clearWidgets();
        selectors.clear();

        // 顶部：数据包类型输入框（自动补全）
        typeSelector.setValue(selected);
        EditBox typeBox = typeSelector.addToScreen(this.font);

        rebuildBody(typeBox.getX() + typeBox.getWidth() + TOOLBAR_GAP);
        if (importMode) {
            buildImportArea();
        } else if (jsonMode) {
            jsonBox = addRenderableWidget(createJsonBox(jsonDraft, text -> {
                jsonDraft = text;
                jsonEdited = true;
                document.userChanged();
            }));
            addRenderableWidget(Button.builder(Component.translatable("htlib.screen.import_clipboard"),
                b -> confirmDiscard(document.isDirty(), () -> pasteClipboard(jsonBox)))
                .bounds(ViewerStyle.LEFT_PADDING, this.height - 30, 95, 18).build());
            focusJsonInput();
        } else {
            layoutForm();
            restoreFormInput(focusKey);
        }
    }

    /** 增量布局：只重摆控件树，不销毁不重建（滚动/平移/结构变化都走这里）。 */
    private void layoutForm() {
        if (!jsonMode && !importMode && formRoot != null) {
            formRoot.widget(this).layout(ViewerStyle.LEFT_PADDING + panX, TOP_OFFSET - formScroll,
                this.width - ViewerStyle.LEFT_PADDING * 2);
        }
    }

    /** 界面移除后不再接收保存/校验结果（响应可能晚于关闭到达）。 */
    @Override
    public void removed() {
        closed = true;
        super.removed();
        EditorResultCache.release(EditorResultPacket.ACTION_SAVE);
        EditorResultCache.release(EditorResultPacket.ACTION_VALIDATE);
    }

    @Override
    public void onClose() {
        confirmDiscard(document.isDirty() || hasImportDraft(), () -> super.onClose());
    }

    /** 叠层确认保留当前屏幕、无效草稿和尚未到达的保存回包。 */
    private void confirmDiscard(boolean dirty, Runnable action) {
        if (!dirty) {
            action.run();
            return;
        }
        if (confirming) {
            return;
        }
        confirming = true;
        Minecraft.getInstance().pushGuiLayer(new ConfirmScreen(discard -> {
            Minecraft.getInstance().popGuiLayer();
            confirming = false;
            if (discard) {
                action.run();
            } else {
                typeSelector.setValue(selected);
                onInit();
            }
        }, Component.translatable("htlib.screen.unsaved_title"),
            Component.translatable("htlib.screen.unsaved_message"),
            Component.translatable("htlib.screen.discard_changes"),
            Component.translatable("htlib.screen.keep_editing")));
    }

    private void onFormChanged() {
        formChanged = true;
        formChangedSinceJson = true;
        document.userChanged();
    }

    private void toggleJsonMode() {
        if (formRoot == null || importMode) {
            return;
        }
        if (jsonMode && jsonEdited) {
            try {
                EditorFormNode candidate = EditorFormNode.create(schema, "", EditorJson.parse(jsonDraft));
                disposeForm();
                formRoot = candidate;
                formRoot.onChanged(this::onFormChanged);
                jsonEdited = false;
                formChanged = false;
            } catch (RuntimeException ex) {
                showJsonError(ex);
                return;
            }
        } else if (!jsonMode) {
            if (formChanged && formRoot.hasErrors()) {
                setStatusPersistent(I18n.get("htlib.screen.invalid_form"));
                return;
            }
            if (formChangedSinceJson) {
                jsonDraft = prettyJson(collectJsonText());
                formChangedSinceJson = false;
            }
        }
        jsonMode = !jsonMode;
        rebuild();
    }

    private void disposeForm() {
        if (formRoot != null && formRoot.hasWidget()) {
            formRoot.widget(this).dispose();
        }
    }

    /**
     * 顶部工具条按钮（模式切换/路径/文件名/动作下拉）。
     */
    private void rebuildBody(int currentLeftOffset) {
        modeButton =
            Button.builder(Component.translatable(jsonMode ? "htlib.screen.form" : "htlib.screen.json"), b -> toggleJsonMode())
                .bounds(currentLeftOffset, 4, MODE_BUTTON_WIDTH, 14).build();
        modeButton.active = !importMode && formRoot != null;
        addRenderableWidget(modeButton);
        currentLeftOffset += MODE_BUTTON_WIDTH + TOOLBAR_GAP;

        int actionsWidth = actionsWidth();
        int actionsX = this.width - ViewerStyle.LEFT_PADDING - actionsWidth;
        int nameFieldsWidth = actionsX - currentLeftOffset - 2 * TOOLBAR_GAP;
        int packWidth = Math.min(80, Math.max(40, nameFieldsWidth / 2));
        EditBox datapackNameField = new EditBox(this.font, currentLeftOffset, 4, packWidth, 13, Component.translatable("htlib.screen.path"));
        datapackNameField.setMaxLength(EditorPacketLimits.MAX_ID_LENGTH);
        datapackNameField.setValue(datapackName);
        datapackNameField.setSuggestion(StringUtils.isBlank(datapackName) ? I18n.get("htlib.screen.datapack_hint") : null);
        datapackNameField.setResponder(s -> {
            if (!datapackName.equals(s)) {
                document.userChanged();
            }
            datapackName = s;
            datapackNameField.setSuggestion(StringUtils.isBlank(s) ? I18n.get("htlib.screen.datapack_hint") : null);
        });
        addRenderableWidget(datapackNameField);
        currentLeftOffset += packWidth + TOOLBAR_GAP;

        EditBox registryNameField = new EditBox(this.font, currentLeftOffset, 4, nameFieldsWidth - packWidth, 13, Component.translatable("htlib.screen.path"));
        registryNameField.setMaxLength(EditorPacketLimits.MAX_ID_LENGTH);
        registryNameField.setValue(saveRegistryName);
        registryNameField.setSuggestion(StringUtils.isBlank(saveRegistryName) ? I18n.get("htlib.screen.path_hint") : null);
        registryNameField.setResponder(s -> {
            if (!saveRegistryName.equals(s)) {
                document.userChanged();
            }
            saveRegistryName = s;
            registryNameField.setSuggestion(StringUtils.isBlank(s) ? I18n.get("htlib.screen.path_hint") : null);
        });
        addRenderableWidget(registryNameField);
        
        // 动作下拉：保存 / 校验 / 导入JSON（按钮文案固定为"更多"）
        List<String> actions = List.of(
            I18n.get("htlib.screen.save"),
            I18n.get("htlib.screen.validate"),
            I18n.get("htlib.screen.import_json")
        );
        saveDropdown =
            new hungteen.htlib.client.gui.widget.codec.DropdownButton(this::addRenderableWidget, this::removeWidget,
                I18n.get("htlib.screen.more"), actionsWidth, 14, actions, 0, index -> {
                if (index == 0) {
                    save();
                } else if (index == 1) {
                    validate();
                } else if (index == 2) {
                    startImportJson();
                }
            });
        saveDropdown.addToScreen(this.font);
        saveDropdown.setPosition(actionsX, 4);
    }

    /**
     * 校验整个表单：交给服务端用完整 RegistryAccess 解析。
     *
     * <p>Holder 引用只有在 {@code RegistryOps} 下才会按注册名解析，而客户端注册表不全，
     * 单纯用 {@code JsonOps} 解析会把它当内联对象，报出误导性的字段错误。</p>
     */
    private void validate() {
        String json = jsonForSubmit();
        if (json == null) {
            return;
        }
        UUID requestId = EditorResultCache.await(EditorResultPacket.ACTION_VALIDATE, this::onValidateResult);
        setStatusPersistent(I18n.get("htlib.screen.validating"));
        NetworkHandler.sendToServer(new ValidateDataPacket(requestId, this.selected, json));
    }

    /** 校验结果：通过只提示成功，不通过给出 codec 的报错原因。 */
    private void onValidateResult(boolean success, String message) {
        setStatusFor(success ? I18n.get("htlib.screen.validate_ok") : I18n.get("htlib.screen.validate_failed", message),
            ViewerStyle.INFO_TIMEOUT_MS);
    }

    /**
     * 把 JSON 文本格式化（多行缩进）。
     */
    private static String prettyJson(String text) {
        try {
            return EditorJson.format(EditorJson.parseValue(text));
        } catch (Exception e) {
            return text;
        }
    }

    /**
     * 选中注册表 → 优先读取 schema 缓存，未命中才请求服务端（同一注册表只请求一次）。
     */
    public void select(String name) {
        if (!name.equals(selected) || formRoot == null) {
            confirmDiscard(document.isDirty() || hasImportDraft(), () -> selectRegistry(name));
        }
    }

    private void selectRegistry(String name) {
        EditorResultCache.release(EditorResultPacket.ACTION_SAVE);
        EditorResultCache.release(EditorResultPacket.ACTION_VALIDATE);
        cancelImportJson();
        if (formRoot != null && formRoot.hasWidget()) {
            formRoot.widget(this).dispose();
        }
        this.selected = name;
        this.schema = null;
        this.formRoot = null;
        document.reset();
        jsonBox = null;
        jsonMode = false;
        jsonDraft = "{}";
        jsonEdited = false;
        formChanged = false;
        formChangedSinceJson = true;
        this.formScroll = 0;
        this.panX = 0;
        String cached = EntrySchemaCache.schema(name);
        if (cached != null && !cached.isEmpty()) {
            applySchema(cached);
            return;
        }
        setStatusPersistent(I18n.get("htlib.screen.request_schema", name));
        rebuild();
        EntrySchemaCache.acquire(name, json -> {
            // 回调时可能已切换/关闭界面：仅当仍是当前选中且屏幕存活时应用
            if (name.equals(selected) && !closed) {
                applySchema(json);
            }
        });
    }

    /**
     * 应用收到的 schema。
     */
    @Override
    protected void applySchema(String schemaJson) {
        try {
            this.schema = SchemaReferences.expand(JsonParser.parseString(schemaJson).getAsJsonObject());
            this.formRoot = EditorFormNode.root(this.schema);
            this.formRoot.onChanged(this::onFormChanged);
            this.formScroll = 0;
            this.panX = 0;
            setStatusFor(I18n.get("htlib.screen.loaded_schema", selected), ViewerStyle.INFO_TIMEOUT_MS);
            rebuild();
        } catch (Exception e) {
            setStatusPersistent(I18n.get("htlib.screen.schema_error", e.getMessage()));
        }
    }

    /**
     * 保存：发送到服务端，由服务端校验通过后落盘。
     */
    private void save() {
        if (StringUtils.isAnyBlank(this.selected, this.saveRegistryName, this.datapackName)) {
            setStatusFor(I18n.get("htlib.screen.missing_path"), ViewerStyle.INFO_TIMEOUT_MS);
            return;
        }
        ResourceLocation registryName = ResourceLocation.tryParse(this.saveRegistryName);
        if (registryName == null || !ResourceLocation.isValidResourceLocation(this.selected)) {
            setStatusFor(I18n.get("htlib.screen.invalid_registry_name"), ViewerStyle.INFO_TIMEOUT_MS);
            return;
        }
        String json = jsonForSubmit();
        if (json == null) {
            return;
        }
        long revision = document.revision();
        UUID requestId = EditorResultCache.await(EditorResultPacket.ACTION_SAVE,
            (success, message) -> onSaveResult(revision, success, message));
        setStatusPersistent(I18n.get("htlib.screen.saving"));
        NetworkHandler.sendToServer(new SaveDataPacket(requestId, selected, datapackName, registryName.toString(), json));
    }

    private String jsonForSubmit() {
        if (importMode) {
            setStatusPersistent(I18n.get("htlib.screen.finish_import"));
            return null;
        }
        if (formRoot == null || (!jsonMode && formRoot.hasErrors())) {
            setStatusFor(I18n.get("htlib.screen.invalid_form"), ViewerStyle.INFO_TIMEOUT_MS);
            return null;
        }
        String json;
        try {
            json = jsonMode || !formChangedSinceJson ? jsonDraft : collectJsonText();
            EditorJson.parse(json);
        } catch (RuntimeException ex) {
            showJsonError(ex);
            return null;
        }
        if (!EditorPacketLimits.fitsJson(json)) {
            setStatusFor(I18n.get("htlib.screen.json_too_large", EditorPacketLimits.MAX_JSON_BYTES), ViewerStyle.INFO_TIMEOUT_MS);
            return null;
        }
        return json;
    }

    /** 保存结果：成功给出落盘路径，失败给出原因（校验不通过 / 写入失败）。 */
    private void onSaveResult(long revision, boolean success, String message) {
        if (success) {
            document.markSaved(revision);
        }
        setStatusFor(success ? I18n.get("htlib.screen.saved", message) : I18n.get("htlib.screen.save_failed", message),
            ViewerStyle.INFO_TIMEOUT_MS);
    }

    private String collectJsonText() {
        if (formRoot != null) {
            return formRoot.collect().toString();
        }
        return "{}";
    }

    // -------------------------------------------------
    // 导入 JSON
    // -------------------------------------------------

    /** 导入区使用独立草稿；解析和确认成功前不替换当前文档。 */
    private void startImportJson() {
        if (formRoot == null) {
            return;
        }
        if (importMode) {
            setFocused(importBox);
            return;
        }
        importMode = true;
        importDraft = "";
        rebuild();
        setFocused(importBox);
    }

    private JsonEditBox createJsonBox(String draft, java.util.function.Consumer<String> onEdit) {
        JsonEditBox box = new JsonEditBox(this.font, ViewerStyle.LEFT_PADDING, TOP_OFFSET,
            Math.max(20, this.width - ViewerStyle.LEFT_PADDING * 2), Math.max(24, this.height - TOP_OFFSET - 68),
            onEdit, this::showInputError);
        box.setDraft(draft);
        return box;
    }

    private void buildImportArea() {
        importBox = addRenderableWidget(createJsonBox(importDraft, text -> importDraft = text));
        int y = this.height - 30;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> confirmImportJson())
            .bounds(ViewerStyle.LEFT_PADDING, y, 55, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("htlib.screen.import_clipboard"),
            b -> confirmDiscard(hasImportDraft(), () -> pasteClipboard(importBox)))
            .bounds(ViewerStyle.LEFT_PADDING + 61, y, 95, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> cancelImportWithConfirmation())
            .bounds(ViewerStyle.LEFT_PADDING + 162, y, 55, 18).build());
        focusJsonInput();
    }

    private boolean hasImportDraft() {
        return importMode && !importDraft.isEmpty();
    }

    private void pasteClipboard(JsonEditBox box) {
        box.importClipboard();
        preferredInput = box;
        focusJsonInput();
    }

    private void cancelImportWithConfirmation() {
        confirmDiscard(hasImportDraft(), () -> {
            cancelImportJson();
            rebuild();
        });
    }

    /** 确认导入：解析 JSON 并加载到表单。 */
    private void confirmImportJson() {
        String text = importDraft;
        try {
            if (!EditorPacketLimits.fitsJson(text)) {
                throw new IllegalArgumentException(I18n.get("htlib.screen.json_too_large", EditorPacketLimits.MAX_JSON_BYTES));
            }
            JsonElement value = EditorJson.parse(text);
            EditorFormNode candidate = EditorFormNode.create(schema, "", value);
            confirmDiscard(document.isDirty(), () -> {
                disposeForm();
                formRoot = candidate;
                formRoot.onChanged(this::onFormChanged);
                jsonDraft = text;
                jsonEdited = false;
                formChanged = false;
                formChangedSinceJson = false;
                document.userChanged();
                cancelImportJson();
                rebuild();
                setStatusFor(I18n.get("htlib.screen.import_json_ok"), ViewerStyle.INFO_TIMEOUT_MS);
            });
        } catch (RuntimeException ex) {
            showJsonError(ex);
        }
    }

    private void showJsonError(RuntimeException ex) {
        setStatusPersistent(I18n.get("htlib.screen.import_json_error", ex.getMessage()));
        focusJsonInput();
    }

    /** 取消导入模式：移除输入框。 */
    private void cancelImportJson() {
        importMode = false;
        importDraft = "";
        if (importBox != null) {
            removeWidget(importBox);
            importBox = null;
        }
        setFocused(null);
    }

    // -------------------------------------------------
    // 渲染
    // -------------------------------------------------

    @Override
    protected void renderFormRegion(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 主体区域裁剪在 TOP_OFFSET 之下：滚动/平移时不会盖住顶部工具条
        graphics.enableScissor(0, TOP_OFFSET, this.width, this.height);

        // 表单：控件树自绘标签（悬浮提示在 renderOverlays 画）
        if (!jsonMode && !importMode && formRoot != null) {
            formRoot.widget(this).render(graphics, this.font, mouseX, mouseY);
        }

        graphics.disableScissor();
    }

    @Override
    protected void renderOverlays(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (modeButton != null) {
            modeButton.setMessage(Component.literal(I18n.get(jsonMode ? "htlib.screen.form" : "htlib.screen.json")
                + (document.isDirty() || hasImportDraft() ? " *" : "")));
        }
        if (!jsonMode && !importMode) {
            drawFormScrollbar(graphics);
            // 面板展开时隐藏字段悬浮提示，避免提示从面板下方探出
            if (formRoot != null && !anyPanelOpen()) {
                formRoot.widget(this).renderTooltip(graphics, this.font, mouseX, mouseY);
            }
        }
    }

    /** 表单右侧滚动条（仅内容超出可视区时显示）。 */
    private void drawFormScrollbar(GuiGraphics graphics) {
        if (formRoot == null) {
            return;
        }
        int content = formRoot.widget(this).height();
        int visible = this.height - TOP_OFFSET - ViewerStyle.BOTTOM_PADDING;
        int maxScroll = Math.max(0, content - visible);
        if (maxScroll <= 0) {
            return;
        }
        int trackX = this.width - ViewerStyle.SCROLLBAR_WIDTH - ViewerStyle.SCROLLBAR_RIGHT_MARGIN;
        int trackY = TOP_OFFSET;
        graphics.fill(trackX - 1, trackY, trackX + ViewerStyle.SCROLLBAR_WIDTH + 1, trackY + visible,
            ViewerStyle.COLOR_SCROLL_TRACK);
        int thumbH = Math.max(EditorWidget.ROW_HEIGHT, visible * visible / Math.max(1, content));
        int thumbY = trackY + (int)((long)formScroll * (visible - thumbH) / maxScroll);
        graphics.fill(trackX, thumbY, trackX + ViewerStyle.SCROLLBAR_WIDTH, thumbY + thumbH,
            ViewerStyle.COLOR_SCROLL_THUMB);
    }

    @Override
    public void tick() {
        super.tick();
        for (var child : children()) {
            if (child instanceof JsonEditBox box) {
                box.tick();
            }
        }
    }

    // -------------------------------------------------
    // 滚动 / 平移
    // -------------------------------------------------

    /** 补全面板之外：表单/JSON 滚动。 */
    @Override
    protected boolean onScroll(double mouseX, double mouseY, double delta) {
        for (var child : children()) {
            if (child instanceof JsonEditBox box && box.visible && box.isMouseOver(mouseX, mouseY)
                && box.mouseScrolled(mouseX, mouseY, delta)) {
                return true;
            }
        }
        if (importMode || jsonMode) {
            return false;
        }
        if (!jsonMode) {
            int content = formRoot == null ? 0 : formRoot.widget(this).height();
            int visible = this.height - TOP_OFFSET - ViewerStyle.BOTTOM_PADDING;
            formScroll = DropdownUtil.clampScroll(formScroll - (int)delta * ViewerStyle.SCROLL_STEP, content, visible);
            layoutForm();
            return true;
        }
        return false;
    }

    /** 补全面板之外：表单右键按下开始平移。 */
    @Override
    protected boolean onMouseClick(double mouseX, double mouseY, int button) {
        if (button == 1 && !jsonMode && !importMode) {
            panning = true;
            panLastX = mouseX;
            panLastY = mouseY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (panning && button == 1) {
            // 内容跟随鼠标：鼠标下/右移 → 内容下/右移
            panX += (int)Math.round(mouseX - panLastX);
            int dy = (int)Math.round(mouseY - panLastY);
            if (!jsonMode) {
                int content = formRoot == null ? 0 : formRoot.widget(this).height();
                int visible = this.height - TOP_OFFSET - ViewerStyle.BOTTOM_PADDING;
                formScroll = DropdownUtil.clampScroll(formScroll - dy, content, visible);
            }
            panX = Math.max(-this.width, Math.min(0, panX));
            panLastX = mouseX;
            panLastY = mouseY;
            layoutForm();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 1) {
            panning = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 动作下拉的面板与字段补全面板一起画在最上层。 */
    @Override
    protected void paintSelectors(GuiGraphics graphics, double mouseX, double mouseY) {
        super.paintSelectors(graphics, mouseX, mouseY);
        if (saveDropdown != null) {
            saveDropdown.paintPanel(graphics, this.font, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        restoreInputFocus = false;
        // 动作下拉展开期间：面板内选择，面板外只收起（不透传给下方控件）
        if (saveDropdown != null && saveDropdown.isOpen()) {
            setFocused(null);
            saveDropdown.mouseClicked(mouseX, mouseY);
            return finishInputEvent(true);
        }
        return finishInputEvent(super.mouseClicked(mouseX, mouseY, button));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        restoreInputFocus = false;
        // 导入模式：Escape 取消
        if (importMode && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            cancelImportWithConfirmation();
            return finishInputEvent(true);
        }
        // 多行输入保留 Enter 换行，Ctrl+Enter 确认导入。
        if (importMode && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && hasControlDown()) {
            confirmImportJson();
            return finishInputEvent(true);
        }
        if (saveDropdown != null && saveDropdown.isOpen() && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            saveDropdown.close();
            return true;
        }
        return finishInputEvent(super.keyPressed(keyCode, scanCode, modifiers));
    }

    // -------------------------------------------------
    // EditorHost
    // -------------------------------------------------

    @Override
    public Screen screen() {
        return this;
    }

    @Override
    public Font font() {
        return this.font;
    }

    @Override
    public int screenWidth() {
        return this.width;
    }

    @Override
    public int topOffset() {
        return TOP_OFFSET;
    }

    @Override
    public void showInputError(String reason) {
        setStatusPersistent(I18n.get("htlib.screen.json_input_rejected", reason));
        focusJsonInput();
    }

    @Override
    protected int statusBottomOffset() {
        return super.statusBottomOffset() + (importMode || jsonMode ? 30 : 0);
    }

    @Override
    public void addWidget(AbstractWidget widget) {
        addRenderableWidget(widget);
    }

    @Override
    public void removeWidget(AbstractWidget widget) {
        super.removeWidget(widget);
    }

    @Override
    public void relayout() {
        layoutForm();
    }

    /** 补全面板右缘不越过右侧按钮列与滚动条区，避免面板盖住 [＋][－][▾] 按钮及其文字。 */
    @Override
    protected int panelRightLimit() {
        return this.width - ViewerStyle.PANEL_RIGHT_GUARD;
    }
}
