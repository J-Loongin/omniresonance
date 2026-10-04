// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Shared list chrome; owners retain filtering, default-cap rows, draft changes and navigation semantics. */
final class TerminalResourceSettingsList {
    private TerminalResourceSettingsList() {}

    static Component text(String key, Object... arguments) {
        return NodeResourcePolicyView.text(key, arguments);
    }

    static TerminalIconButton add(TerminalLayout.Rect bounds, boolean active, Runnable action) {
        var button = new TerminalIconButton(
                bounds.x(), bounds.y(), bounds.width(), bounds.height(), text("add_type"), ignored -> action.run());
        button.active = active;
        return button;
    }

    static Component entryLabel(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative resource override count");
        return text("settings_entry", count == 0 ? text("defaults") : text("override_count", count));
    }

    static TerminalButton entry(TerminalLayout.Rect bounds, int count, boolean active, Runnable open) {
        var label = entryLabel(count);
        var button = new TerminalButton(
                bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height(),
                label,
                pressed -> {
                    if (pressed.active) open.run();
                },
                false);
        button.active = active;
        button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(TerminalText.body(label)));
        return button;
    }

    record Layout(TerminalLayout.Rect notice, RoutingListLayout list) {}

    record Row(Component label, Component tooltip, @Nullable Component unavailableId, Runnable edit) {}

    interface Entries {
        int size();

        Row row(int index);
    }

    static Layout page(TerminalLayout.Rect body, int count, int scroll) {
        var rows = new TerminalLayout.Rect(
                body.x() + 4, body.y() + 26, Math.max(0, body.width() - 8), Math.max(0, body.height() - 26));
        return new Layout(
                new TerminalLayout.Rect(body.x() + 8, body.y() + 5, Math.max(0, body.width() - 16), 10),
                RoutingListLayout.calculateRows(rows, count, scroll));
    }

    static void buildRows(
            TerminalLayout.Rect body,
            Entries entries,
            int scroll,
            boolean active,
            java.util.function.Consumer<TerminalRowButton> add) {
        var list = page(body, entries.size(), scroll).list();
        for (int index = list.scroll(); index < Math.min(entries.size(), list.scroll() + list.visibleRows()); index++) {
            var entry = entries.row(index);
            var row = new TerminalRowButton(
                    list.row(index - list.scroll()),
                    entry.label(),
                    ignored -> entry.edit().run());
            row.active = active;
            if (entry.unavailableId() != null) row.statusSuffix(entry.unavailableId(), text("unavailable_type", ""));
            row.setTooltip(net.minecraft.client.gui.components.Tooltip.create(TerminalText.body(entry.tooltip())));
            add.accept(row);
        }
    }

    static void renderPage(GuiGraphics graphics, Font font, TerminalLayout.Rect body, int count, int scroll) {
        var list = page(body, count, scroll).list();
        renderNotice(graphics, font, body);
        if (count == 0) renderEmpty(graphics, font, list.rows());
        TerminalTheme.renderScrollbar(
                graphics,
                list.scrollbar().x(),
                list.scrollbar().y(),
                list.scrollbar().height(),
                count,
                list.visibleRows(),
                list.scroll());
    }

    static Component summary(Component type, Component rate, Component unit, @Nullable Component batch) {
        var summary = type.copy().append(" · ").append(text("rate_summary", rate, unit));
        if (batch != null) summary.append(" · ").append(batch);
        return summary;
    }

    static void renderNotice(GuiGraphics graphics, Font font, TerminalLayout.Rect body) {
        NodeResourcePolicyView.label(
                graphics, font, body.x() + 8, body.y() + 5, Math.max(0, body.width() - 16), text("defaults_notice"));
    }

    static void renderEmpty(GuiGraphics graphics, Font font, TerminalLayout.Rect rows) {
        NodeResourcePolicyView.label(
                graphics, font, rows.x() + 4, rows.y() + 8, Math.max(0, rows.width() - 8), text("empty_types"));
    }
}
