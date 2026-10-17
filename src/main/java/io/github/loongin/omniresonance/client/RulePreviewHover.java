// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** One view-owned pointer inspection. The fixed grid and one optional name have no input or inventory authority. */
final class RulePreviewHover {
    private @Nullable TerminalRowButton owner;
    private @Nullable RulePreviewGrid grid;
    private @Nullable TerminalLayout.Rect bounds, bridge;
    private @Nullable Component name;
    private int anchorX, anchorY, viewportWidth, viewportHeight;

    void clear() {
        if (owner != null && owner.icon() instanceof ResourceIconPreview preview) preview.clearGrid();
        owner = null;
        grid = null;
        bounds = bridge = null;
        name = null;
    }

    @Nullable
    Component name() {
        return name;
    }

    @Nullable
    TerminalLayout.Rect bounds() {
        return bounds;
    }

    @Nullable
    RulePreviewGrid grid() {
        return grid;
    }

    void update(List<TerminalRowButton> rows, int width, int height, int mouseX, int mouseY) {
        name = null;
        if (owner != null && (!rows.contains(owner) || !owner.visible)) clear();
        if (owner != null && bounds != null && !inside(owner, mouseX, mouseY) && inside(bounds, mouseX, mouseY)) {
            ((ResourceIconPreview) owner.icon()).hover();
            var image = grid.imageAt(bounds, mouseX, mouseY);
            name = image == null ? null : image.name();
            return;
        }
        if (owner != null && bridge != null && !inside(owner, mouseX, mouseY) && inside(bridge, mouseX, mouseY)) {
            ((ResourceIconPreview) owner.icon()).hover();
            return;
        }
        for (var row : rows) {
            if (!row.visible || !inside(row, mouseX, mouseY) || !(row.icon() instanceof ResourceIconPreview preview))
                continue;
            preview.hover();
            int right = row.iconRight(), top = row.getY() + (row.getHeight() - 16) / 2;
            if (mouseX >= right - preview.width() && mouseX < right && mouseY >= top && mouseY < top + 16) {
                clear();
                for (int i = 0; i < preview.inlineCount(); i++) {
                    int left = right - 16 - 18 * i;
                    if (mouseX >= left && mouseX < left + 16) {
                        name = preview.inlineImage(i).name();
                        break;
                    }
                }
                return;
            }
            if (row == owner) {
                position(width, height, mouseX, mouseY);
                return;
            }
            clear();
            var next = preview.grid();
            if (next == null) return;
            owner = row;
            grid = next;
            position(width, height, mouseX, mouseY);
            return;
        }
        clear();
    }

    private void position(int width, int height, int mouseX, int mouseY) {
        if (bounds != null
                && anchorX == mouseX
                && anchorY == mouseY
                && viewportWidth == width
                && viewportHeight == height) return;
        anchorX = mouseX;
        anchorY = mouseY;
        viewportWidth = width;
        viewportHeight = height;
        bounds = grid.bounds(width, height, mouseX, mouseY);
        // A narrow bridge spans only the gap/overlap between the row and fixed grid.
        int x1 = Math.max(owner.getX(), bounds.x()), x2 = Math.min(owner.getRight(), bounds.right());
        int y1 = Math.max(owner.getY(), bounds.y()), y2 = Math.min(owner.getBottom(), bounds.bottom());
        bridge = new TerminalLayout.Rect(
                Math.min(x1, x2) - 3, Math.min(y1, y2) - 3, Math.abs(x2 - x1) + 6, Math.abs(y2 - y1) + 6);
    }

    private static boolean inside(TerminalRowButton row, int x, int y) {
        return x >= row.getX() && x < row.getRight() && y >= row.getY() && y < row.getBottom();
    }

    private static boolean inside(TerminalLayout.Rect rect, int x, int y) {
        return x >= rect.x() && x < rect.right() && y >= rect.y() && y < rect.bottom();
    }

    void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (grid != null) grid.render(graphics, bounds);
        if (name != null) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 400);
            try {
                graphics.renderTooltip(font, TerminalText.body(name), mouseX, mouseY);
            } finally {
                graphics.pose().popPose();
            }
        }
    }
}
