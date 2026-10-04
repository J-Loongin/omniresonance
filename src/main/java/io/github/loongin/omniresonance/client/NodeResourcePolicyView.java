// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.RedstoneCondition;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Adaptive common fields and one resource-settings entry above fixed footer actions; only visible rows exist. */
final class NodeResourcePolicyView {
    static final int ROW_HEIGHT = 32;

    enum RowKind {
        COMMON,
        TYPE_HEADER
    }

    record Actions(
            Runnable changed,
            Runnable rebuild,
            Runnable choosePreset,
            Runnable changeDirection,
            Runnable chooseFaces,
            Runnable chooseScope,
            Runnable chooseType,
            Runnable save) {}

    record Layout(TerminalLayout.Rect form, int firstRow, int visibleRows, int totalRows, boolean threeColumns) {
        int logicalRow(int row) {
            return threeColumns && row >= 3 ? row + 1 : row;
        }

        TerminalLayout.Rect row(int index) {
            if (index < firstRow || index >= firstRow + visibleRows)
                throw new IllegalArgumentException("Invisible resource form row");
            return new TerminalLayout.Rect(
                    form.x(), form.y() + (index - firstRow) * ROW_HEIGHT, form.width(), ROW_HEIGHT);
        }
    }

    record TypeControls(
            TerminalLayout.Rect rate, TerminalLayout.Rect mode, TerminalLayout.Rect batch, boolean batchEnabled) {}

    private NodeResourcePolicyView() {}

    static Layout layout(TerminalLayout.Rect body, int scroll, NodeResourcePolicyDraft draft) {
        body = TerminalActionLayout.of(body).content();
        boolean wide = body.width() >= 340;
        int total = totalRows(draft) - (wide ? 1 : 0);
        int visible = Math.min(total, Math.max(1, (body.height() - 8) / ROW_HEIGHT));
        return new Layout(
                new TerminalLayout.Rect(body.x() + 12, body.y() + 4, body.width() - 24, visible * ROW_HEIGHT),
                Math.clamp(scroll, 0, Math.max(0, total - visible)),
                visible,
                total,
                wide);
    }

    static int totalRows(NodeResourcePolicyDraft draft) {
        return 5;
    }

    static RowKind rowKind(NodeResourcePolicyDraft draft, int row) {
        if (row < 0 || row > 4) throw new IllegalArgumentException("Invalid common row");
        return row < 4 ? RowKind.COMMON : RowKind.TYPE_HEADER;
    }

    static TypeControls typeControls(TerminalLayout.Rect row, NodeResourcePolicyDraft draft, ResourceLocation id) {
        int width = (row.width() - 12) / 3;
        return new TypeControls(
                new TerminalLayout.Rect(
                        row.x(), row.y() + 10, draft.direction == TransferDirection.INPUT ? width : row.width(), 20),
                new TerminalLayout.Rect(row.x() + width + 6, row.y() + 10, width, 20),
                new TerminalLayout.Rect(row.x() + (width + 6) * 2, row.y() + 10, row.width() - (width + 6) * 2, 20),
                draft.direction == TransferDirection.INPUT
                        && draft.type(id).batchMode == ResourceTransferPolicy.BatchMode.EXACT);
    }

    static void build(
            Font font,
            Layout layout,
            NodeResourcePolicyDraft draft,
            boolean active,
            Consumer<AbstractWidget> add,
            Component workingFaces,
            Actions actions) {
        for (int row = layout.firstRow(); row < layout.firstRow() + layout.visibleRows(); row++) {
            var bounds = layout.row(row);
            switch (rowKind(draft, layout.logicalRow(row))) {
                case COMMON ->
                    common(font, bounds, row, layout.threeColumns(), draft, active, add, workingFaces, actions);
                case TYPE_HEADER ->
                    add.accept(TerminalResourceSettingsList.entry(
                            new TerminalLayout.Rect(bounds.x(), bounds.y() + 6, bounds.width(), 20),
                            draft.settingIds().size(),
                            active,
                            actions.chooseType()));
            }
        }
    }

    enum Field {
        DIRECTION,
        SCOPE,
        REDSTONE,
        PRESET,
        FILTER,
        INTERVAL,
        QUANTITY,
        FACES
    }

    record Cell(Field field, TerminalLayout.Rect bounds) {}

    static java.util.List<Cell> commonCells(TerminalLayout.Rect row, int index, boolean wide) {
        Field[][] fields = wide
                ? new Field[][] {
                    {Field.DIRECTION, Field.SCOPE, Field.REDSTONE},
                    {Field.PRESET, Field.FILTER},
                    {Field.INTERVAL, Field.QUANTITY, Field.FACES}
                }
                : new Field[][] {
                    {Field.DIRECTION, Field.SCOPE},
                    {Field.PRESET, Field.FILTER},
                    {Field.INTERVAL, Field.QUANTITY},
                    {Field.REDSTONE, Field.FACES}
                };
        int columns = wide ? 3 : 2;
        var cells = new java.util.ArrayList<Cell>();
        int column = 0;
        for (Field field : fields[index]) {
            int span = wide && field == Field.PRESET ? 2 : 1;
            cells.add(new Cell(field, TerminalFormGrid.control(row, columns, column, span)));
            column += span;
        }
        return cells;
    }

    private static void common(
            Font font,
            TerminalLayout.Rect row,
            int index,
            boolean wide,
            NodeResourcePolicyDraft draft,
            boolean active,
            Consumer<AbstractWidget> add,
            Component faces,
            Actions actions) {
        for (Cell cell : commonCells(row, index, wide)) {
            var bounds = cell.bounds();
            switch (cell.field()) {
                case DIRECTION ->
                    formField(
                                    add,
                                    bounds,
                                    text(draft.direction == TransferDirection.INPUT ? "input_short" : "output_short"),
                                    active,
                                    actions.changeDirection())
                            .setTooltip(
                                    Tooltip.create(TerminalText.body(NodeItemPolicyView.direction(draft.direction))));
                case SCOPE -> formField(add, bounds, scopeText(draft), active, actions.chooseScope());
                case PRESET ->
                    formField(
                            add,
                            bounds,
                            draft.presetId == null
                                    ? legacy("no_preset")
                                    : draft.presetName == null
                                            ? legacy("missing_preset")
                                            : io.github.loongin.omniresonance.filter.BuiltInPresets.label(
                                                    draft.presetId, draft.presetName),
                            active,
                            actions.choosePreset());
                case FILTER ->
                    formField(
                            add,
                            bounds,
                            legacy(draft.filterMode == FilterMode.WHITELIST ? "whitelist" : "blacklist"),
                            active,
                            () -> {
                                draft.filterMode = draft.filterMode == FilterMode.WHITELIST
                                        ? FilterMode.BLACKLIST
                                        : FilterMode.WHITELIST;
                                actions.changed().run();
                                actions.rebuild().run();
                            });
                case REDSTONE ->
                    formField(add, bounds, NodeItemPolicyView.redstone(draft.redstone), active, () -> {
                        draft.redstone = RedstoneCondition.values()[(draft.redstone.ordinal() + 1) % 3];
                        actions.changed().run();
                        actions.rebuild().run();
                    });
                case FACES -> formField(add, bounds, faces, active, actions.chooseFaces());
                case QUANTITY ->
                    field(
                            font,
                            add,
                            bounds,
                            legacy(draft.direction == TransferDirection.INPUT ? "keep" : "priority"),
                            draft.quantity,
                            active,
                            draft.direction == TransferDirection.INPUT
                                    ? text("keep.help")
                                    : draft.domain ? text("domain_priority.help") : legacy("priority.help"),
                            value -> {
                                draft.quantity = value;
                                actions.changed().run();
                            });
                case INTERVAL -> {
                    var field = new TerminalIntervalBox(
                            font, bounds.x(), bounds.y(), bounds.width(), bounds.height(), legacy("interval"));
                    field.setMaxLength(20);
                    field.setValue(draft.interval);
                    field.setResponder(value -> {
                        draft.interval = value;
                        actions.changed().run();
                    });
                    field.setEditable(active);
                    field.active = active;
                    field.setTooltip(Tooltip.create(
                            TerminalText.body(text(draft.domain ? "domain_interval.help" : "interval.help")
                                    .copy()
                                    .append("\n")
                                    .append(text("interval_wheel")))));
                    add.accept(field);
                }
            }
        }
    }

    private static Component fieldLabel(Field field, NodeResourcePolicyDraft draft) {
        return switch (field) {
            case DIRECTION -> legacy("direction");
            case SCOPE -> text("scope");
            case REDSTONE -> legacy("redstone");
            case PRESET -> legacy("preset");
            case FILTER -> legacy("filter_mode");
            case INTERVAL -> legacy("interval");
            case QUANTITY -> text(draft.direction == TransferDirection.INPUT ? "keep_short" : "priority_short");
            case FACES -> legacy(draft.direction == TransferDirection.INPUT ? "extraction_faces" : "output_faces");
        };
    }

    static void render(GuiGraphics graphics, Font font, Layout layout, NodeResourcePolicyDraft draft) {
        for (int row = layout.firstRow(); row < layout.firstRow() + layout.visibleRows(); row++) {
            var bounds = layout.row(row);
            switch (rowKind(draft, layout.logicalRow(row))) {
                case COMMON -> {
                    for (Cell cell : commonCells(bounds, row, layout.threeColumns())) {
                        label(
                                graphics,
                                font,
                                cell.bounds().x(),
                                bounds.y(),
                                cell.bounds().width(),
                                fieldLabel(cell.field(), draft));
                    }
                }
                case TYPE_HEADER -> {}
            }
        }
    }

    static Component scopeText(NodeResourcePolicyDraft draft) {
        return draft.scope().kind() == ResourceScope.Kind.ALL
                ? text("scope_all_short")
                : text("scope_custom_short", draft.scope().ids().size());
    }

    static Component typeName(ResourceLocation id) {
        if (id.equals(ResourceTypes.ITEM)) return text("type.item");
        if (id.equals(ResourceTypes.FLUID)) return text("type.fluid");
        if (id.equals(ResourceTypes.ENERGY)) return text("type.energy");
        return Component.translatableWithFallback(
                "omniresonance.resource_type." + id.getNamespace() + "." + id.getPath(), id.toString());
    }

    static Component text(String suffix, Object... args) {
        return Component.translatable("omniresonance.resource_policy." + suffix, args);
    }

    private static Component legacy(String suffix) {
        return Component.translatable("omniresonance.item_policy." + suffix);
    }

    static void label(GuiGraphics graphics, Font font, int x, int y, int width, Component label) {
        graphics.drawString(
                font,
                TerminalText.body(Component.literal(TerminalText.ellipsize(font, label.getString(), width))),
                x,
                y,
                TerminalTheme.MUTED,
                false);
    }

    private static TerminalRowButton formField(
            Consumer<AbstractWidget> add,
            TerminalLayout.Rect bounds,
            Component label,
            boolean active,
            Runnable action) {
        var button = TerminalFormGrid.field(bounds, label, active, action);
        add.accept(button);
        return button;
    }

    static TerminalButton button(
            Consumer<AbstractWidget> add, TerminalLayout.Rect bounds, Component text, boolean active, Runnable action) {
        var button = new TerminalButton(
                bounds.x(), bounds.y(), bounds.width(), bounds.height(), text, ignored -> action.run(), false);
        button.active = active;
        button.setTooltip(Tooltip.create(TerminalText.body(text)));
        add.accept(button);
        return button;
    }

    static void field(
            Font font,
            Consumer<AbstractWidget> add,
            TerminalLayout.Rect bounds,
            Component label,
            String value,
            boolean active,
            Component help,
            Consumer<String> changed) {
        var field = new TerminalEditBox(font, bounds.x(), bounds.y(), bounds.width(), bounds.height(), label);
        field.setMaxLength(20);
        field.setValue(value);
        field.setResponder(changed);
        field.setEditable(active);
        field.active = active;
        field.setTooltip(Tooltip.create(TerminalText.body(help)));
        add.accept(field);
    }
}
