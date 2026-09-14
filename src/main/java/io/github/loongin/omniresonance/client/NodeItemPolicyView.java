// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.RedstoneCondition;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Four paired item-policy rows with an explicit compact header save action. */
final class NodeItemPolicyView {
    private static final int ROW_HEIGHT = 32;

    private NodeItemPolicyView() {}

    record Layout(TerminalLayout.Rect form, TerminalLayout.Rect actions, int firstRow, int visibleRows) {
        TerminalLayout.Rect row(int index) {
            if (index < firstRow || index >= firstRow + visibleRows)
                throw new IllegalArgumentException("Invisible form row");
            return new TerminalLayout.Rect(
                    form.x(), form.y() + (index - firstRow) * ROW_HEIGHT, form.width(), ROW_HEIGHT);
        }
    }

    static Layout layout(TerminalLayout.Rect body, int scroll) {
        var actions = new TerminalLayout.Rect(body.right() - 76, body.y() + 4, 64, 20);
        var form = new TerminalLayout.Rect(body.x() + 12, body.y() + 28, body.width() - 24, 128);
        return new Layout(form, actions, 0, 4);
    }

    static void build(
            Font font,
            Layout layout,
            NodeItemPolicyDraft draft,
            boolean active,
            Consumer<AbstractWidget> add,
            Runnable changed,
            Runnable rebuild,
            Runnable choosePreset,
            Runnable changeDirection,
            Component workingFaces,
            Runnable chooseFaces,
            Runnable save) {
        for (int index = layout.firstRow(); index < layout.firstRow() + layout.visibleRows(); index++) {
            var row = layout.row(index);
            int half = (row.width() - 8) / 2;
            int y = row.y() + 10;
            switch (index) {
                case 0 -> {
                    button(add, row.x(), y, half, direction(draft.direction), active, changeDirection);
                    field(font, add, row.x() + half + 8, y, half, "rate", draft.rate, active, value -> {
                        draft.rate = value;
                        changed.run();
                    });
                }
                case 1 -> {
                    Component name = draft.presetId == null
                            ? text("no_preset")
                            : draft.presetName == null ? text("missing_preset") : Component.literal(draft.presetName);
                    button(add, row.x(), y, half, name, active, choosePreset);
                    button(
                            add,
                            row.x() + half + 8,
                            y,
                            half,
                            text(draft.filterMode == FilterMode.WHITELIST ? "whitelist" : "blacklist"),
                            active,
                            () -> {
                                draft.filterMode = draft.filterMode == FilterMode.WHITELIST
                                        ? FilterMode.BLACKLIST
                                        : FilterMode.WHITELIST;
                                changed.run();
                                rebuild.run();
                            });
                }
                case 2 -> {
                    field(font, add, row.x(), y, half, "interval", draft.interval, active, value -> {
                        draft.interval = value;
                        changed.run();
                    });
                    field(
                            font,
                            add,
                            row.x() + half + 8,
                            y,
                            half,
                            draft.direction == TransferDirection.INPUT ? "keep" : "priority",
                            draft.quantity,
                            active,
                            value -> {
                                draft.quantity = value;
                                changed.run();
                            });
                }
                case 3 -> {
                    button(add, row.x(), y, half, redstone(draft.redstone), active, () -> {
                        draft.redstone = RedstoneCondition.values()[(draft.redstone.ordinal() + 1) % 3];
                        changed.run();
                        rebuild.run();
                    });
                    button(add, row.x() + half + 8, y, half, workingFaces, active, chooseFaces);
                }
                default -> throw new IllegalStateException("Unknown item form row");
            }
        }
        button(
                add,
                layout.actions().x(),
                layout.actions().y(),
                layout.actions().width(),
                Component.translatable("omniresonance.node_menu.save"),
                active,
                save);
    }

    static void render(GuiGraphics graphics, Font font, Layout layout, NodeItemPolicyDraft draft) {
        for (int index = layout.firstRow(); index < layout.firstRow() + layout.visibleRows(); index++) {
            var row = layout.row(index);
            int half = (row.width() - 8) / 2;
            String left =
                    switch (index) {
                        case 0 -> "direction";
                        case 1 -> "preset";
                        case 2 -> "interval";
                        default -> "redstone";
                    };
            label(graphics, font, row.x(), row.y(), half, text(left));
            String right =
                    switch (index) {
                        case 0 -> "rate";
                        case 1 -> "filter_mode";
                        case 2 -> draft.direction == TransferDirection.INPUT ? "keep" : "priority";
                        case 3 -> draft.direction == TransferDirection.INPUT ? "extraction_faces" : "output_faces";
                        default -> null;
                    };
            if (right != null) label(graphics, font, row.x() + half + 8, row.y(), half, text(right));
        }
    }

    private static void label(GuiGraphics g, Font font, int x, int y, int width, Component label) {
        g.drawString(
                font,
                TerminalText.body(Component.literal(TerminalText.ellipsize(font, label.getString(), width))),
                x,
                y,
                TerminalTheme.MUTED,
                false);
    }

    private static void field(
            Font font,
            Consumer<AbstractWidget> add,
            int x,
            int y,
            int width,
            String key,
            String value,
            boolean active,
            Consumer<String> change) {
        TerminalEditBox box = new TerminalEditBox(font, x, y, width, 20, text(key));
        box.setMaxLength(20);
        box.setValue(value);
        box.setResponder(change);
        box.setEditable(active);
        box.setTooltip(Tooltip.create(TerminalText.body(text(key + ".help"))));
        add.accept(box);
    }

    private static void button(
            Consumer<AbstractWidget> add, int x, int y, int width, Component label, boolean active, Runnable action) {
        TerminalButton button = new TerminalButton(x, y, width, 20, label, ignored -> action.run(), false);
        button.active = active;
        button.setTooltip(Tooltip.create(TerminalText.body(label)));
        add.accept(button);
    }

    static Component text(String suffix) {
        return Component.translatable("omniresonance.item_policy." + suffix);
    }

    static Component direction(TransferDirection direction) {
        return Component.translatable(
                "omniresonance.node_menu.direction." + direction.name().toLowerCase(Locale.ROOT));
    }

    static Component redstone(RedstoneCondition condition) {
        return text("redstone." + condition.name().toLowerCase(Locale.ROOT));
    }
}
