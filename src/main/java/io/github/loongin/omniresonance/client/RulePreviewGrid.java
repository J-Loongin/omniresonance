// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;

/** One immutable hover snapshot. Image-only foreground, never a widget, click target or scrolling surface. */
record RulePreviewGrid(List<RulePreviewImage> images, boolean more) {
    static final int COLUMNS = 6, CELL = 18, PADDING = 4;

    RulePreviewGrid {
        if (images.isEmpty() || images.size() > RuleIconPreview.MAXIMUM_IMAGES)
            throw new IllegalArgumentException("Invalid rule preview grid size");
        images = List.copyOf(images);
    }

    int width() {
        return COLUMNS * CELL + PADDING * 2;
    }

    int height() {
        return ((images.size() + COLUMNS - 1) / COLUMNS) * CELL + PADDING * 2 + (more ? 6 : 0);
    }

    TerminalLayout.Rect bounds(int screenWidth, int screenHeight, int mouseX, int mouseY) {
        int x = mouseX + 12;
        if (x + width() > screenWidth - PADDING) x = mouseX - 12 - width();
        int y = mouseY + 12;
        if (y + height() > screenHeight - PADDING) y = mouseY - 12 - height();
        x = Math.clamp(x, PADDING, Math.max(PADDING, screenWidth - width() - PADDING));
        y = Math.clamp(y, PADDING, Math.max(PADDING, screenHeight - height() - PADDING));
        return new TerminalLayout.Rect(x, y, width(), height());
    }

    TerminalLayout.Rect cell(TerminalLayout.Rect bounds, int index) {
        if (index < 0 || index >= images.size()) throw new IndexOutOfBoundsException(index);
        return new TerminalLayout.Rect(
                bounds.x() + PADDING + index % COLUMNS * CELL,
                bounds.y() + PADDING + index / COLUMNS * CELL,
                CELL,
                CELL);
    }

    @org.jetbrains.annotations.Nullable
    RulePreviewImage imageAt(TerminalLayout.Rect bounds, int mouseX, int mouseY) {
        int x = mouseX - bounds.x() - PADDING, y = mouseY - bounds.y() - PADDING;
        if (x < 0
                || y < 0
                || x >= COLUMNS * CELL
                || y >= ((images.size() + COLUMNS - 1) / COLUMNS) * CELL
                || x % CELL < 1
                || x % CELL >= 17
                || y % CELL < 1
                || y % CELL >= 17) return null;
        int index = y / CELL * COLUMNS + x / CELL;
        return index < images.size() ? images.get(index) : null;
    }

    void render(GuiGraphics graphics, TerminalLayout.Rect bounds) {
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        try {
            TerminalTheme.renderPanel(graphics, bounds);
            for (int i = 0; i < images.size(); i++) {
                var cell = cell(bounds, i);
                TerminalTheme.renderSlot(graphics, cell.x(), cell.y(), CELL, false);
                images.get(i).render(graphics, cell.x() + 1, cell.y() + 1);
            }
            if (more) {
                int x = bounds.right() - PADDING - 10, y = bounds.bottom() - PADDING - 2;
                for (int i = 0; i < 3; i++) graphics.fill(x + i * 4, y, x + i * 4 + 2, y + 2, TerminalTheme.MUTED);
            }
        } finally {
            graphics.pose().popPose();
        }
    }
}
