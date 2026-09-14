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
import org.jetbrains.annotations.Nullable;

/** Four paired common rows, sparse collapsed type settings and a compact in-flow save; only visible rows exist. */
final class NodeResourcePolicyView {
    static final int ROW_HEIGHT = 32;

    enum RowKind {
        COMMON,
        TYPE_HEADER,
        TYPE_NAME,
        TYPE_VALUES,
        MISSING_TYPE,
        EMPTY_TYPES,
        ADD_TYPE,
        SAVE
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

    record Layout(TerminalLayout.Rect form, int firstRow, int visibleRows, int totalRows) {
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
        int total = totalRows(draft);
        int visible = Math.min(total, Math.max(1, (body.height() - 16) / ROW_HEIGHT));
        return new Layout(
                new TerminalLayout.Rect(body.x() + 12, body.y() + 8, body.width() - 24, visible * ROW_HEIGHT),
                Math.clamp(scroll, 0, Math.max(0, total - visible)),
                visible,
                total);
    }

    static int totalRows(NodeResourcePolicyDraft draft) {
        return 6 + (draft.expanded ? 1 + Math.max(1, draft.settingIds().size() + draft.knownSettingCount()) : 0);
    }

    static RowKind rowKind(NodeResourcePolicyDraft draft, int row) {
        if (row < 0 || row >= totalRows(draft)) throw new IllegalArgumentException("Invalid resource form row");
        if (row < 4) return RowKind.COMMON;
        if (row == 4) return RowKind.TYPE_HEADER;
        if (row == totalRows(draft) - 1) return RowKind.SAVE;
        if (row == totalRows(draft) - 2) return RowKind.ADD_TYPE;
        if (draft.settingIds().isEmpty()) return RowKind.EMPTY_TYPES;
        int entry = row - 5;
        if (entry < draft.knownSettingCount() * 2) return entry % 2 == 0 ? RowKind.TYPE_NAME : RowKind.TYPE_VALUES;
        return RowKind.MISSING_TYPE;
    }

    static ResourceLocation rowType(NodeResourcePolicyDraft draft, int row) {
        int entry = row - 5;
        return draft.settingIds()
                .get(entry < draft.knownSettingCount() * 2 ? entry / 2 : entry - draft.knownSettingCount());
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
            switch (rowKind(draft, row)) {
                case COMMON -> common(font, bounds, row, draft, active, add, workingFaces, actions);
                case TYPE_HEADER ->
                    button(add, right(bounds, 80), text(draft.expanded ? "collapse" : "expand"), active, () -> {
                        draft.expanded = !draft.expanded;
                        actions.rebuild().run();
                    });
                case TYPE_NAME, MISSING_TYPE -> {
                    ResourceLocation id = rowType(draft, row);
                    button(
                            add,
                            right(bounds, 80),
                            text(draft.unavailable(id) ? "delete" : "restore_default"),
                            active,
                            () -> {
                                draft.restoreDefault(id);
                                actions.changed().run();
                                actions.rebuild().run();
                            });
                }
                case TYPE_VALUES -> {
                    ResourceLocation id = rowType(draft, row);
                    var type = draft.type(id);
                    var controls = typeControls(bounds, draft, id);
                    field(
                            font,
                            add,
                            controls.rate(),
                            text("rate"),
                            type.rate,
                            active,
                            text(
                                    "rate.help",
                                    id.equals(ResourceTypes.ITEM)
                                            ? text("unit.item")
                                            : Component.literal(
                                                    draft.catalog.find(id).unit())),
                            value -> {
                                type.rate = value;
                                actions.changed().run();
                            });
                    if (draft.direction == TransferDirection.INPUT) {
                        button(
                                add,
                                controls.mode(),
                                text(type.batchMode == ResourceTransferPolicy.BatchMode.GREEDY ? "greedy" : "exact"),
                                active,
                                () -> {
                                    type.batchMode = type.batchMode == ResourceTransferPolicy.BatchMode.GREEDY
                                            ? ResourceTransferPolicy.BatchMode.EXACT
                                            : ResourceTransferPolicy.BatchMode.GREEDY;
                                    actions.changed().run();
                                    actions.rebuild().run();
                                });
                        field(
                                font,
                                add,
                                controls.batch(),
                                text("batch"),
                                type.batch,
                                active && controls.batchEnabled(),
                                text("batch.help"),
                                value -> {
                                    type.batch = value;
                                    actions.changed().run();
                                });
                    }
                }
                case ADD_TYPE ->
                    button(
                            add,
                            new TerminalLayout.Rect(bounds.x(), bounds.y() + 6, Math.min(bounds.width(), 160), 20),
                            text("add_type"),
                            active,
                            actions.chooseType());
                case SAVE ->
                    button(
                            add,
                            right(bounds, 64),
                            Component.translatable("omniresonance.node_menu.save"),
                            active,
                            actions.save());
                case EMPTY_TYPES -> {}
            }
        }
    }

    private static void common(
            Font font,
            TerminalLayout.Rect row,
            int index,
            NodeResourcePolicyDraft draft,
            boolean active,
            Consumer<AbstractWidget> add,
            Component faces,
            Actions actions) {
        int half = (row.width() - 8) / 2;
        var left = new TerminalLayout.Rect(row.x(), row.y() + 10, half, 20);
        var right = new TerminalLayout.Rect(row.x() + half + 8, row.y() + 10, row.width() - half - 8, 20);
        switch (index) {
            case 0 -> {
                button(add, left, NodeItemPolicyView.direction(draft.direction), active, actions.changeDirection());
                button(add, right, scopeText(draft), active, actions.chooseScope());
            }
            case 1 -> {
                Component preset = draft.presetId == null
                        ? legacy("no_preset")
                        : draft.presetName == null ? legacy("missing_preset") : Component.literal(draft.presetName);
                button(add, left, preset, active, actions.choosePreset());
                button(
                        add,
                        right,
                        legacy(draft.filterMode == FilterMode.WHITELIST ? "whitelist" : "blacklist"),
                        active,
                        () -> {
                            draft.filterMode = draft.filterMode == FilterMode.WHITELIST
                                    ? FilterMode.BLACKLIST
                                    : FilterMode.WHITELIST;
                            actions.changed().run();
                            actions.rebuild().run();
                        });
            }
            case 2 -> {
                field(
                        font,
                        add,
                        new TerminalLayout.Rect(left.x(), left.y(), left.width() - 60, left.height()),
                        legacy("interval"),
                        draft.interval,
                        active,
                        text("interval.help"),
                        value -> {
                            draft.interval = value;
                            actions.changed().run();
                        });
                int[] intervals = {1, 5, 20};
                for (int quick = 0; quick < intervals.length; quick++) {
                    String value = Integer.toString(intervals[quick]);
                    button(
                            add,
                            new TerminalLayout.Rect(left.right() - 58 + quick * 20, left.y(), 18, 20),
                            Component.literal(value),
                            active,
                            () -> {
                                draft.interval = value;
                                actions.changed().run();
                                actions.rebuild().run();
                            });
                }
                String quantity = draft.direction == TransferDirection.INPUT ? "keep" : "priority";
                field(
                        font,
                        add,
                        right,
                        legacy(quantity),
                        draft.quantity,
                        active,
                        draft.direction == TransferDirection.INPUT ? text("keep.help") : legacy(quantity + ".help"),
                        value -> {
                            draft.quantity = value;
                            actions.changed().run();
                        });
            }
            case 3 -> {
                button(add, left, NodeItemPolicyView.redstone(draft.redstone), active, () -> {
                    draft.redstone = RedstoneCondition.values()[(draft.redstone.ordinal() + 1) % 3];
                    actions.changed().run();
                    actions.rebuild().run();
                });
                button(add, right, faces, active, actions.chooseFaces());
            }
            default -> throw new IllegalArgumentException("Unknown common resource row");
        }
    }

    static void render(GuiGraphics graphics, Font font, Layout layout, NodeResourcePolicyDraft draft) {
        for (int row = layout.firstRow(); row < layout.firstRow() + layout.visibleRows(); row++) {
            var bounds = layout.row(row);
            switch (rowKind(draft, row)) {
                case COMMON -> {
                    int half = (bounds.width() - 8) / 2;
                    String left =
                            switch (row) {
                                case 0 -> "direction";
                                case 1 -> "preset";
                                case 2 -> "interval";
                                default -> "redstone";
                            };
                    Component right =
                            switch (row) {
                                case 0 -> text("scope");
                                case 1 -> legacy("filter_mode");
                                case 2 -> legacy(draft.direction == TransferDirection.INPUT ? "keep" : "priority");
                                default ->
                                    legacy(
                                            draft.direction == TransferDirection.INPUT
                                                    ? "extraction_faces"
                                                    : "output_faces");
                            };
                    label(graphics, font, bounds.x(), bounds.y(), half, legacy(left));
                    label(graphics, font, bounds.x() + half + 8, bounds.y(), half, right);
                }
                case TYPE_HEADER ->
                    label(
                            graphics,
                            font,
                            bounds.x(),
                            bounds.y() + 11,
                            bounds.width() - 88,
                            draft.settingIds().isEmpty()
                                    ? text("defaults")
                                    : text("override_count", draft.settingIds().size()));
                case TYPE_NAME, MISSING_TYPE -> {
                    ResourceLocation id = rowType(draft, row);
                    label(
                            graphics,
                            font,
                            bounds.x(),
                            bounds.y() + 11,
                            bounds.width() - 88,
                            draft.unavailable(id) ? text("unavailable_type", id.toString()) : typeName(id));
                }
                case TYPE_VALUES -> {
                    var controls = typeControls(bounds, draft, rowType(draft, row));
                    label(
                            graphics,
                            font,
                            controls.rate().x(),
                            bounds.y(),
                            controls.rate().width(),
                            text("rate"));
                    if (draft.direction == TransferDirection.INPUT) {
                        label(
                                graphics,
                                font,
                                controls.mode().x(),
                                bounds.y(),
                                controls.mode().width(),
                                text("batch_mode"));
                        label(
                                graphics,
                                font,
                                controls.batch().x(),
                                bounds.y(),
                                controls.batch().width(),
                                text("batch"));
                    }
                }
                case ADD_TYPE, SAVE -> {}
                case EMPTY_TYPES ->
                    label(graphics, font, bounds.x(), bounds.y() + 11, bounds.width(), text("empty_types"));
            }
        }
    }

    static Component scopeText(NodeResourcePolicyDraft draft) {
        return draft.scope().kind() == ResourceScope.Kind.ALL
                ? text("scope_all")
                : text("scope_custom", draft.scope().ids().size());
    }

    @Nullable
    static Component tooltip(Layout layout, NodeResourcePolicyDraft draft, double mouseX, double mouseY) {
        for (int row = layout.firstRow(); row < layout.firstRow() + layout.visibleRows(); row++) {
            RowKind kind = rowKind(draft, row);
            if (kind != RowKind.TYPE_NAME && kind != RowKind.MISSING_TYPE) continue;
            var bounds = layout.row(row);
            if (mouseX < bounds.x()
                    || mouseX >= bounds.right() - 88
                    || mouseY < bounds.y()
                    || mouseY >= bounds.bottom()) continue;
            ResourceLocation id = rowType(draft, row);
            return draft.unavailable(id)
                    ? text("unavailable_type", id.toString())
                    : Component.literal(typeName(id).getString() + "\n" + id);
        }
        return null;
    }

    static Component typeName(ResourceLocation id) {
        if (id.equals(ResourceTypes.ITEM)) return text("type.item");
        if (id.equals(ResourceTypes.FLUID)) return text("type.fluid");
        if (id.equals(ResourceTypes.ENERGY)) return text("type.energy");
        return Component.literal(id.toString());
    }

    static Component text(String suffix, Object... args) {
        return Component.translatable("omniresonance.resource_policy." + suffix, args);
    }

    private static Component legacy(String suffix) {
        return Component.translatable("omniresonance.item_policy." + suffix);
    }

    private static TerminalLayout.Rect right(TerminalLayout.Rect row, int width) {
        return new TerminalLayout.Rect(row.right() - width, row.y() + 6, width, 20);
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

    static TerminalButton button(
            Consumer<AbstractWidget> add, TerminalLayout.Rect bounds, Component text, boolean active, Runnable action) {
        var button = new TerminalButton(
                bounds.x(), bounds.y(), bounds.width(), bounds.height(), text, ignored -> action.run(), false);
        button.active = active;
        button.setTooltip(Tooltip.create(TerminalText.body(text)));
        add.accept(button);
        return button;
    }

    private static void field(
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
