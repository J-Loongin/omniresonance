// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Only explicitly added resource settings are listed; modal edits remain detached until local Apply. */
final class NodeResourceSettingsView {
    private NodeResourceSettingsView() {}

    static RoutingListLayout list(TerminalLayout.Rect body, NodeResourcePolicyDraft draft, int scroll) {
        return TerminalResourceSettingsList.page(body, draft.settingIds().size(), scroll)
                .list();
    }

    static void buildList(
            TerminalLayout.Rect body,
            NodeResourcePolicyDraft draft,
            int scroll,
            boolean active,
            Consumer<AbstractWidget> add,
            Consumer<ResourceLocation> edit) {
        TerminalResourceSettingsList.buildRows(
                body,
                new TerminalResourceSettingsList.Entries() {
                    @Override
                    public int size() {
                        return draft.settingIds().size();
                    }

                    @Override
                    public TerminalResourceSettingsList.Row row(int index) {
                        var id = draft.settingIds().get(index);
                        var label = summary(draft, id);
                        return new TerminalResourceSettingsList.Row(
                                label,
                                label.copy().append("\n").append(id.toString()),
                                draft.unavailable(id) ? Component.literal(id.toString()) : null,
                                () -> edit.accept(id));
                    }
                },
                scroll,
                active,
                row -> add.accept(row));
    }

    static Component unit(NodeResourcePolicyDraft draft, ResourceLocation id) {
        return id.equals(ResourceTypes.ITEM)
                ? NodeResourcePolicyView.text("unit.item")
                : Component.literal(draft.catalog.find(id).unit());
    }

    static Component summary(NodeResourcePolicyDraft draft, ResourceLocation id) {
        if (draft.unavailable(id)) return NodeResourcePolicyView.text("unavailable_type", id.toString());
        var value = draft.type(id);
        return TerminalResourceSettingsList.summary(
                NodeResourcePolicyView.typeName(id),
                Component.literal(value.rate),
                unit(draft, id),
                draft.direction == TransferDirection.INPUT
                        ? value.batchMode == ResourceTransferPolicy.BatchMode.GREEDY
                                ? NodeResourcePolicyView.text("greedy")
                                : NodeResourcePolicyView.text("batch_summary", value.batch)
                        : null);
    }

    static TerminalLayout.Rect dialog(TerminalLayout.Rect body, NodeResourceSettingEditor edit) {
        return TerminalResourceParameterLayout.of(body, edit.direction == TransferDirection.INPUT, edit.unavailable)
                .dialog();
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
        var geometry =
                TerminalResourceParameterLayout.of(body, edit.direction == TransferDirection.INPUT, edit.unavailable);
        var dialog = geometry.dialog();
        if (edit.unavailable) {
            action(add, TerminalActionLayout.of(dialog).secondary(), "cancel", active, false, cancel);
            action(add, TerminalActionLayout.of(dialog).primary(), "delete", active, true, restore);
        } else {
            TerminalResourceParameterView.build(
                    font,
                    geometry,
                    form(edit, active),
                    edit.bindings(changed, rebuild),
                    new TerminalResourceParameterView.Actions(restore, cancel, apply),
                    add);
        }
    }

    static TerminalResourceParameterView.Form form(NodeResourceSettingEditor edit, boolean active) {
        return edit.form(unit(edit.owner, edit.id), edit.direction == TransferDirection.INPUT, active);
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
        var geometry =
                TerminalResourceParameterLayout.of(body, edit.direction == TransferDirection.INPUT, edit.unavailable);
        var dialog = geometry.dialog();
        if (edit.unavailable) {
            TerminalTheme.renderDialogPanel(graphics, dialog);
            TerminalText.drawDialogTitle(graphics, font, NodeResourcePolicyView.typeName(edit.id), dialog);
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
        TerminalResourceParameterView.render(
                graphics,
                font,
                geometry,
                form(edit, true),
                edit.invalid ? NodeResourcePolicyView.text("invalid") : null);
    }
}
