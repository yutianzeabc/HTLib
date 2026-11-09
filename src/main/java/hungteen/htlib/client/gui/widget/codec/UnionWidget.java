package hungteen.htlib.client.gui.widget.codec;

import hungteen.htlib.client.gui.screen.codec.EditorHost;
import hungteen.htlib.client.gui.screen.codec.node.EditorFormNode;
import hungteen.htlib.client.gui.screen.codec.node.UnionNode;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.HashMap;
import java.util.Map;

/**
 * 联合（dispatch）控件：字段标签 + 类型补全选择器 + 当前分支的子控件。
 * 非当前分支的子控件整体隐藏，切换分支时复用（不销毁重建）。
 * @author PangTeen
 * @program HTLib
 * @create 2026/9/5 22:50
 */
public final class UnionWidget extends EditorWidget {

    private final UnionNode node;
    private final Map<Integer, EditorWidget> variantWidgets = new HashMap<>();
    private TypeSelector selector;

    public UnionWidget(EditorHost host, UnionNode node) {
        super(host, node);
        this.node = node;
    }

    @Override
    protected void create() {
        if (node.unionNames().isEmpty()) {
            return;
        }
//        if (collapsible()) {
//            createCollapseToggle();
//        }
        selector = createSelector(SELECTOR_WIDTH, node.unionNames(), node.currentVariantName(), name -> {
            int idx = node.unionNames().indexOf(name);
            if (idx >= 0) {
                node.selectVariant(idx);
                EditorWidget widget = variantWidgets.get(idx);
                if (widget != null && widget.node() == node.selectedVariant()) {
                    widget.refreshFromNode();
                }
                host.relayout();
            }
        });
    }

    @Override
    protected boolean collapsible() {
        return !node.unionNames().isEmpty();
    }

    @Override
    protected String displayLabel() {
        String label = super.displayLabel();
        return label.isEmpty() ? node.discriminatorKey() : label;
    }

    @Override
    protected int doLayout() {
//        placeCollapseToggle();
        if (selector != null) {
            selector.setPosition(controlX(), y);
            selector.setVisible(!hidden && rowVisible(y));
        }
//        if (isCollapsed()) {
//            for (EditorWidget widget : variantWidgets) {
//                if (widget != null) {
//                    widget.setHidden(true);
//                }
//            }
//            return ROW_HEIGHT;
//        }
        EditorFormNode selected = node.selectedVariant();
        int idx = node.variantIndex();
        for (var entry : variantWidgets.entrySet()) {
            if (entry.getKey() != idx || selected == null) {
                entry.getValue().setHidden(true);
            }
        }
        int height = ROW_HEIGHT;
        if (selected != null) {
            EditorWidget widget = variantWidgets.get(idx);
            if (widget == null || widget.node() != selected) {
                if (widget != null) {
                    widget.dispose();
                }
                widget = selected.widget(host);
                variantWidgets.put(idx, widget);
            }
            height += widget.layout(x - INDENT, y + ROW_HEIGHT, width + INDENT);
        }
        return height;
    }

    @Override
    protected void doRender(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        drawLabel(graphics, font, displayLabel(), labelColor());
        EditorWidget widget = selectedWidget();
        if (widget != null) {
            widget.render(graphics, font, mouseX, mouseY);
        }
    }

    @Override
    public boolean renderTooltip(GuiGraphics graphics, Font font, double mouseX, double mouseY) {
        if (renderSelfTooltip(graphics, font, mouseX, mouseY)) {
            return true;
        }
        EditorWidget widget = selectedWidget();
        return widget != null && widget.renderTooltip(graphics, font, mouseX, mouseY);
    }

    @Override
    public void refreshFromNode() {
        if (!isCreated()) {
            return;
        }
        if (selector == null && !node.unionNames().isEmpty()) {
            create();
        }
        if (selector != null) {
            selector.setSuggestions(node.unionNames());
            selector.setValue(node.currentVariantName());
        }
        EditorWidget widget = selectedWidget();
        if (widget != null) {
            widget.refreshFromNode();
        }
    }

    @Override
    public void setHidden(boolean hidden) {
        super.setHidden(hidden);
        if (hidden) {
            for (EditorWidget widget : variantWidgets.values()) {
                widget.setHidden(true);
            }
        }
    }

    @Override
    protected void onDispose() {
        removeSelector(selector);
        selector = null;
        for (EditorWidget widget : variantWidgets.values()) {
            widget.dispose();
        }
        variantWidgets.clear();
    }

    private EditorWidget selectedWidget() {
        EditorWidget widget = variantWidgets.get(node.variantIndex());
        return widget != null && widget.node() == node.selectedVariant() ? widget : null;
    }
}
