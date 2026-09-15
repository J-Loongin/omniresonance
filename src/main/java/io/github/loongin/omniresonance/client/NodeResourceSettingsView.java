// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Only explicitly added resource settings are listed; modal edits remain detached until local Apply. */
final class NodeResourceSettingsView {
    private NodeResourceSettingsView() {}

    static RoutingListLayout list(TerminalLayout.Rect body, NodeResourcePolicyDraft draft, int scroll) {
        return RoutingListLayout.calculate(body, draft.settingIds().size(), scroll);
    }

    static void buildList(
            TerminalLayout.Rect body,
            NodeResourcePolicyDraft draft,
            int scroll,
            boolean active,
            Consumer<AbstractWidget> add,
            Consumer<ResourceLocation> edit) {
        var layout = list(body, draft, scroll);
        for (int row = 0;
                row < layout.visibleRows()
                        && row + layout.scroll() < draft.settingIds().size();
                row++) {
            var id = draft.settingIds().get(row + layout.scroll());
            Component summary = summary(draft, id);
            var button = new TerminalRowButton(layout.row(row), summary, ignored -> edit.accept(id));
            button.active = active;
            button.setTooltip(
                    Tooltip.create(TerminalText.body(summary.copy().append("\n").append(id.toString()))));
            add.accept(button);
        }
    }

    static Component unit(NodeResourcePolicyDraft draft, ResourceLocation id) {
        return id.equals(ResourceTypes.ITEM)
                ? NodeResourcePolicyView.text("unit.item")
                : Component.literal(draft.catalog.find(id).unit());
    }

    static Component summary(NodeResourcePolicyDraft draft, ResourceLocation id) {
        if (draft.unavailable(id)) return NodeResourcePolicyView.text("unavailable_type", id.toString());
        var value = draft.type(id);
        var result = NodeResourcePolicyView.typeName(id)
                .copy()
                .append(" · ")
                .append(NodeResourcePolicyView.text("rate_summary", value.rate, unit(draft, id)));
        if (draft.direction == TransferDirection.INPUT)
            result.append(" · ")
                    .append(
                            value.batchMode == ResourceTransferPolicy.BatchMode.GREEDY
                                    ? NodeResourcePolicyView.text("greedy")
                                    : NodeResourcePolicyView.text("batch_summary", value.batch));
        return result;
    }

    static TerminalLayout.Rect dialog(TerminalLayout.Rect body, NodeResourceSettingEditor edit) {
        return TerminalDialogLayout.centered(
                body, 300, edit.unavailable ? 110 : edit.direction == TransferDirection.INPUT ? 156 : 124);
    }

    static void buildEditor(
            Font font,
            TerminalLayout.Rect body,
            NodeResourceSettingEditor edit,
            boolean active,
            Consumer<AbstractWidget> add,
            Runnable changed,
            Runnable rebuild,
            Runnable cancel,
            Runnable apply,
            Runnable restore) {
        var dialog = dialog(body, edit);
        if (!edit.unavailable) {
            NodeResourcePolicyView.field(
                    font,
                    add,
                    new TerminalLayout.Rect(dialog.x() + 12, dialog.y() + 42, dialog.width() - 24, 20),
                    NodeResourcePolicyView.text("rate"),
                    edit.rate,
                    active,
                    NodeResourcePolicyView.text("rate.help", unit(edit.owner, edit.id)),
                    value -> {
                        edit.rate = value;
                        edit.invalid = false;
                        changed.run();
                    });
            if (edit.direction == TransferDirection.INPUT) {
                int half = (dialog.width() - 30) / 2;
                NodeResourcePolicyView.button(
                        add,
                        new TerminalLayout.Rect(dialog.x() + 12, dialog.y() + 80, half, 20),
                        NodeResourcePolicyView.text(
                                edit.mode == ResourceTransferPolicy.BatchMode.GREEDY ? "greedy" : "exact"),
                        active,
                        () -> {
                            edit.mode = edit.mode == ResourceTransferPolicy.BatchMode.GREEDY
                                    ? ResourceTransferPolicy.BatchMode.EXACT
                                    : ResourceTransferPolicy.BatchMode.GREEDY;
                            changed.run();
                            rebuild.run();
                        });
                NodeResourcePolicyView.field(
                        font,
                        add,
                        new TerminalLayout.Rect(dialog.x() + 18 + half, dialog.y() + 80, half, 20),
                        NodeResourcePolicyView.text("batch"),
                        edit.batch,
                        active && edit.mode == ResourceTransferPolicy.BatchMode.EXACT,
                        NodeResourcePolicyView.text("batch.help"),
                        value -> {
                            edit.batch = value;
                            edit.invalid = false;
                            changed.run();
                        });
            }
        }
        if (edit.unavailable) {
            action(add, TerminalActionLayout.of(dialog).secondary(), "cancel", active, false, cancel);
            action(add, TerminalActionLayout.of(dialog).primary(), "delete", active, true, restore);
        } else {
            action(add, TerminalActionLayout.button(dialog, 3, 0), "restore_default", active, false, restore);
            action(add, TerminalActionLayout.button(dialog, 3, 1), "cancel", active, false, cancel);
            action(add, TerminalActionLayout.button(dialog, 3, 2), "apply", active, true, apply);
        }
    }

    private static void action(
            Consumer<AbstractWidget> add,
            TerminalLayout.Rect bounds,
            String key,
            boolean active,
            boolean primary,
            Runnable action) {
        var button = new TerminalButton(
                bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height(),
                NodeResourcePolicyView.text(key),
                ignored -> action.run(),
                primary);
        button.active = active;
        add.accept(button);
    }

    static void renderEditor(
            GuiGraphics graphics, Font font, TerminalLayout.Rect body, NodeResourceSettingEditor edit) {
        var dialog = dialog(body, edit);
        TerminalTheme.renderPanel(graphics, dialog);
        NodeResourcePolicyView.label(
                graphics,
                font,
                dialog.x() + 12,
                dialog.y() + 12,
                dialog.width() - 24,
                NodeResourcePolicyView.typeName(edit.id));
        if (edit.unavailable) {
            graphics.drawWordWrap(
                    font,
                    TerminalText.body(NodeResourcePolicyView.text(
                            "unavailable_type", TerminalText.ellipsize(font, edit.id.toString(), dialog.width() - 40))),
                    dialog.x() + 12,
                    dialog.y() + 32,
                    dialog.width() - 24,
                    TerminalTheme.MUTED);
            return;
        }
        NodeResourcePolicyView.label(
                graphics,
                font,
                dialog.x() + 12,
                dialog.y() + 30,
                dialog.width() - 24,
                NodeResourcePolicyView.text("rate_unit", unit(edit.owner, edit.id)));
        if (edit.direction == TransferDirection.INPUT) {
            int half = (dialog.width() - 30) / 2;
            NodeResourcePolicyView.label(
                    graphics, font, dialog.x() + 12, dialog.y() + 68, half, NodeResourcePolicyView.text("batch_mode"));
            NodeResourcePolicyView.label(
                    graphics,
                    font,
                    dialog.x() + 18 + half,
                    dialog.y() + 68,
                    half,
                    NodeResourcePolicyView.text("batch"));
        }
        if (edit.invalid)
            NodeResourcePolicyView.label(
                    graphics,
                    font,
                    dialog.x() + 12,
                    dialog.bottom() - 42,
                    dialog.width() - 24,
                    NodeResourcePolicyView.text("invalid"));
    }
}
