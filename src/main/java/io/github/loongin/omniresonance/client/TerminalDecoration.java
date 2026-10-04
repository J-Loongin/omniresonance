// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Explicit four-times nine-slice UVs; the power-of-two padding is never sampled. */
final class TerminalDecoration {
    static final int WIDTH = 2048, HEIGHT = 1024, EFFECTIVE_WIDTH = 1520, EFFECTIVE_HEIGHT = 920, EDGE = 12;
    private static final int SOURCE_EDGE = EDGE * 4;

    private TerminalDecoration() {}

    static TerminalLayout.Rect source(int row, int column) {
        if (row < 0 || row > 2 || column < 0 || column > 2 || row == 1 && column == 1)
            throw new IllegalArgumentException("Only decorative outer slices may be sampled");
        int x = column == 0 ? 0 : column == 1 ? SOURCE_EDGE : EFFECTIVE_WIDTH - SOURCE_EDGE;
        int y = row == 0 ? 0 : row == 1 ? SOURCE_EDGE : EFFECTIVE_HEIGHT - SOURCE_EDGE;
        return new TerminalLayout.Rect(
                x,
                y,
                column == 1 ? EFFECTIVE_WIDTH - SOURCE_EDGE * 2 : SOURCE_EDGE,
                row == 1 ? EFFECTIVE_HEIGHT - SOURCE_EDGE * 2 : SOURCE_EDGE);
    }
}
