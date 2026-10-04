// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;
/** Shared label-above-control geometry for native terminal forms. */
final class TerminalFormGrid {
    private TerminalFormGrid() {}

    static TerminalRowButton field(
            TerminalLayout.Rect bounds, net.minecraft.network.chat.Component label, boolean active, Runnable action) {
        var button = new TerminalRowButton(bounds, label, ignored -> action.run());
        button.setTextInset(4);
        button.active = active;
        button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(TerminalText.body(label)));
        return button;
    }

    static TerminalLayout.Rect trailingAction(TerminalLayout.Rect body, int y, int width) {
        int available = Math.max(0, body.width() - 16);
        return new TerminalLayout.Rect(
                body.right() - 8 - Math.min(width, available), y, Math.min(width, available), 20);
    }

    static TerminalLayout.Rect control(TerminalLayout.Rect row, int columns, int column, int span) {
        if (columns < 1 || column < 0 || span < 1 || column + span > columns)
            throw new IllegalArgumentException("Invalid form cell");
        int unit = Math.max(0, (row.width() - (columns - 1) * 6) / columns);
        int x = row.x() + column * (unit + 6);
        int width = column + span == columns ? Math.max(0, row.right() - x) : span * unit + (span - 1) * 6;
        return new TerminalLayout.Rect(x, row.y() + 10, width, 20);
    }
}
