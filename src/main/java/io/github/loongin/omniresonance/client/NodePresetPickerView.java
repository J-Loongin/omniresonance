// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.FilterPresetSummary;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

/** Compact search plus wheel-only choices, sharing the screen's fixed body. */
final class NodePresetPickerView {
    private NodePresetPickerView() {}

    static TerminalLayout.Rect search(TerminalLayout.Rect body) {
        return NodeRoutingView.tunnelSearchBounds(body);
    }

    static TerminalLayout.Rect rows(TerminalLayout.Rect body) {
        return rows(body, false);
    }

    static TerminalLayout.Rect rows(TerminalLayout.Rect body, boolean expanded) {
        return NodeRoutingView.tunnelList(body, expanded, 0, 0).rows();
    }

    @org.jetbrains.annotations.Nullable
    static TerminalSearchBox buildSearch(
            net.minecraft.client.gui.Font font, TerminalLayout.Rect body, NodePresetPicker picker, Runnable changed) {
        if (!picker.search().expanded()) return null;
        var bounds = search(body);
        var field = new TerminalSearchBox(
                font, bounds.x(), bounds.y(), bounds.width(), bounds.height(), NodeItemPolicyView.text("search"));
        field.setMaxLength(256);
        field.setHint(TerminalText.body(NodeItemPolicyView.text("search")));
        field.setValue(picker.query());
        field.setResponder(value -> {
            picker.edit(value);
            changed.run();
        });
        return field;
    }

    static int visibleRows(TerminalLayout.Rect body) {
        return visibleRows(body, false);
    }

    static int visibleRows(TerminalLayout.Rect body, boolean expanded) {
        return NodeRoutingView.tunnelList(body, expanded, 0, 0).visibleRows();
    }

    static void buildRows(
            TerminalLayout.Rect body,
            NodePresetPicker picker,
            boolean enabled,
            Consumer<TerminalRowButton> add,
            Consumer<FilterPresetSummary> choose) {
        var layout = NodeRoutingView.tunnelList(body, picker.search().expanded(), picker.count(), picker.scroll());
        int visible = layout.visibleRows();
        picker.viewport(visible);
        var page = picker.page();
        if (page == null) return;
        for (int index = picker.scroll(); index < Math.min(picker.count(), picker.scroll() + visible); index++) {
            FilterPresetSummary preset =
                    page.offset() == 0 && index == 0 ? null : page.entries().get(index - (page.offset() == 0 ? 1 : 0));
            var row = layout.row(index - picker.scroll());
            var button = new TerminalRowButton(
                    row,
                    preset == null ? NodeItemPolicyView.text("no_preset") : Component.literal(preset.name()),
                    ignored -> choose.accept(preset));
            button.active = enabled && picker.ready();
            add.accept(button);
        }
    }
}
