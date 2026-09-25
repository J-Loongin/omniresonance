// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;
/** Shared label-above-control geometry for native terminal forms. */
final class TerminalFormGrid {
    private TerminalFormGrid() {}

    static TerminalLayout.Rect control(TerminalLayout.Rect row, int columns, int column, int span) {
        if (columns < 1 || column < 0 || span < 1 || column + span > columns)
            throw new IllegalArgumentException("Invalid form cell");
        int unit = Math.max(0, (row.width() - (columns - 1) * 6) / columns);
        int x = row.x() + column * (unit + 6);
        int width = column + span == columns ? Math.max(0, row.right() - x) : span * unit + (span - 1) * 6;
        return new TerminalLayout.Rect(x, row.y() + 10, width, 20);
    }
}
