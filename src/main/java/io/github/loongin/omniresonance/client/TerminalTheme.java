// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** Fixed code-rendered colors and geometry shared by terminal screens and controls. */
final class TerminalTheme {
    static final int WORLD_DIM = 0x94000000;
    static final int WINDOW_TOP = 0xEF0C1D29;
    static final int WINDOW_BOTTOM = 0xED071119;
    static final int TITLE = 0xE60C2635;
    static final int PANEL = 0xCC142A38;
    static final int RAISED = 0xD51E3948;
    static final int RAISED_HOVERED = 0xDB25536A;
    static final int RAISED_DISABLED = 0xB51A2C37;
    static final int ACCENT_SOFT = 0xA9238CA6;
    static final int ACCENT = 0xFF54D7EE;
    static final int LINE = 0x9054BED8;
    static final int TEXT = 0xFFEAF8FB;
    static final int MUTED = 0xFF95ADB8;
    static final int ERROR = 0xFFFF7D86;
    static final int SCANLINE = 0x1054D7EE;

    static final int OUTER_RADIUS = 8;
    static final int PANEL_RADIUS = 5;
    static final int BUTTON_RADIUS = 4;

    private TerminalTheme() {}

    static void renderWindow(GuiGraphics graphics, TerminalLayout layout) {
        TerminalLayout.Rect window = layout.window();
        fillRounded(
                graphics, window.x() - 1, window.y() - 1, window.width() + 2, window.height() + 2, OUTER_RADIUS, LINE);
        fillRounded(graphics, window.x(), window.y(), window.width(), window.height(), OUTER_RADIUS, WINDOW_BOTTOM);
        graphics.fillGradient(
                window.x() + 2, window.y() + 2, window.right() - 2, window.bottom() - 2, WINDOW_TOP, WINDOW_BOTTOM);
        TerminalLayout.Rect title = layout.titleBar();
        graphics.fill(title.x() + 1, title.y() + 1, title.right() - 1, title.bottom(), TITLE);
        graphics.fill(title.x() + 1, title.y() + 1, title.right() - 1, title.y() + 2, ACCENT_SOFT);
        graphics.fill(title.x() + 1, title.bottom() - 1, title.right() - 1, title.bottom(), LINE);
        for (int y = window.y() + 5; y < window.bottom() - 2; y += 6) {
            graphics.fill(window.x() + 2, y, window.right() - 2, y + 1, SCANLINE);
        }
    }

    static void renderPanel(GuiGraphics graphics, TerminalLayout.Rect bounds) {
        fillRounded(
                graphics, bounds.x() - 1, bounds.y() - 1, bounds.width() + 2, bounds.height() + 2, PANEL_RADIUS, LINE);
        fillRounded(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), PANEL_RADIUS, PANEL);
    }

    static void renderScrollbar(
            GuiGraphics graphics, int x, int y, int height, int totalRows, int visibleRows, int firstRow) {
        if (height <= 0 || totalRows <= visibleRows) {
            return;
        }
        graphics.fill(x, y, x + TerminalLayout.SCROLLBAR_WIDTH, y + height, 0x70101F29);
        int thumbHeight = Math.max(10, height * visibleRows / totalRows);
        int range = height - thumbHeight;
        int maximumFirst = Math.max(1, totalRows - visibleRows);
        int thumbY = y + range * Mth.clamp(firstRow, 0, maximumFirst) / maximumFirst;
        fillRounded(graphics, x + 1, thumbY, TerminalLayout.SCROLLBAR_WIDTH - 2, thumbHeight, 2, ACCENT_SOFT);
    }

    static void fillRounded(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int corner = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        if (corner == 0) {
            graphics.fill(x, y, x + width, y + height, color);
            return;
        }
        graphics.fill(x + corner, y, x + width - corner, y + height, color);
        graphics.fill(x, y + corner, x + width, y + height - corner, color);
        int square = corner * corner;
        for (int row = 0; row < corner; row++) {
            int dy = corner - row - 1;
            int inset = corner - (int) Math.floor(Math.sqrt(Math.max(0, square - dy * dy)));
            graphics.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
            graphics.fill(x + inset, y + height - row - 1, x + width - inset, y + height - row, color);
        }
    }
}
