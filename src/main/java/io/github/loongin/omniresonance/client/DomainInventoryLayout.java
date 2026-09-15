// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Compact terminal geometry shared by rendering, hit testing, scrolling and player-slot mapping. */
record DomainInventoryLayout(TerminalLayout.Rect body, boolean inventory) {
    static final int CELL = 18;

    static TerminalLayout window(int screenWidth, int screenHeight) {
        int width = 220, height = Math.max(216, Math.min(360, screenHeight - 12));
        int x = (screenWidth - width) / 2, y = (screenHeight - height) / 2;
        return new TerminalLayout(
                new TerminalLayout.Rect(x, y, width, height),
                new TerminalLayout.Rect(x, y, width, 24),
                new TerminalLayout.Rect(x + 6, y + 30, width - 12, height - 36),
                true);
    }

    static void renderWindow(net.minecraft.client.gui.GuiGraphics graphics, TerminalLayout layout) {
        var w = layout.window();
        TerminalTheme.fillRounded(graphics, w.x() - 1, w.y() - 1, w.width() + 2, w.height() + 2, 3, 0xFF35616A);
        TerminalTheme.fillRounded(graphics, w.x(), w.y(), w.width(), w.height(), 3, 0xFF242D32);
        graphics.fill(w.x() + 2, w.y() + 2, w.right() - 2, layout.titleBar().bottom(), 0xFF303D44);
        graphics.fill(
                w.x() + 3,
                layout.titleBar().bottom(),
                w.right() - 3,
                layout.titleBar().bottom() + 1,
                0xFF142126);
        graphics.fill(w.x() + 3, w.y() + 2, w.right() - 3, w.y() + 3, 0xFF4C8590);
    }

    int gridX() {
        return body.x() + 26;
    }

    int gridY() {
        return body.y() + 26;
    }

    int columns() {
        return Math.min(9, Math.max(1, (body.width() - 40) / CELL));
    }

    int inventoryY() {
        return body.bottom() - 80;
    }

    int statusY() {
        return inventory ? inventoryY() - 10 : body.bottom() - 12;
    }

    int rows() {
        return Math.max(1, (statusY() - 4 - gridY()) / CELL);
    }

    int gridRight() {
        return gridX() + columns() * CELL;
    }

    int gridBottom() {
        return gridY() + rows() * CELL;
    }

    int slotX(int index) {
        return gridX() + (index % 9) * CELL;
    }

    int slotY(int index) {
        return inventoryY() + (index < 9 ? 58 : ((index - 9) / 9) * CELL);
    }

    int inventorySlot(double x, double y) {
        if (!inventory) return -1;
        for (int i = 0; i < 36; i++)
            if (x >= slotX(i) && x < slotX(i) + CELL && y >= slotY(i) && y < slotY(i) + CELL) return i;
        return -1;
    }

    boolean inGrid(double x, double y) {
        return x >= gridX() && x < gridRight() && y >= gridY() && y < gridBottom();
    }
}
