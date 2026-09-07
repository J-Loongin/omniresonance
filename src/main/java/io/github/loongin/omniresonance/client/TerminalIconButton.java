// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Shared circular creation action, drawn on integer pixels independently of font fallback. */
final class TerminalIconButton extends TerminalClickButton {
    private static final int DIAMETER = 18;
    private static final int[] OUTER_INSETS = {6, 4, 3, 2, 1, 1, 0, 0, 0};
    private static final int[] INNER_INSETS = {9, 6, 4, 3, 2, 2, 1, 1, 1};

    TerminalIconButton(int x, int y, int width, int height, Component label, OnPress onPress) {
        super(x, y, width, height, TerminalText.body(label), onPress);
        setTooltip(Tooltip.create(TerminalText.body(label)));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawIcon(getX(), getY(), getWidth(), getHeight(), active, isHoveredOrFocused(), graphics::fill);
    }

    static void drawIcon(
            int x, int y, int width, int height, boolean active, boolean hoveredOrFocused, PixelFill fill) {
        if (width < DIAMETER || height < DIAMETER) {
            return;
        }
        int left = x + (width - DIAMETER) / 2;
        int top = y + (height - DIAMETER) / 2;
        boolean highlighted = active && hoveredOrFocused;
        int ring = highlighted ? TerminalTheme.ACCENT : TerminalTheme.LINE;
        int surface = highlighted ? TerminalTheme.ICON_HOVERED : TerminalTheme.ICON_IDLE;
        int cross = active ? highlighted ? TerminalTheme.ACCENT : TerminalTheme.MUTED : TerminalTheme.LINE;

        // Non-overlapping spans keep the translucent ring and center at their intended opacity.
        for (int row = 0; row < DIAMETER; row++) {
            int index = Math.min(row, DIAMETER - row - 1);
            int outer = OUTER_INSETS[index];
            int inner = INNER_INSETS[index];
            fill.draw(left + outer, top + row, left + inner, top + row + 1, ring);
            if (inner < DIAMETER / 2) {
                fill.draw(left + inner, top + row, left + DIAMETER - inner, top + row + 1, surface);
            }
            fill.draw(left + DIAMETER - inner, top + row, left + DIAMETER - outer, top + row + 1, ring);
        }
        fill.draw(left + 4, top + 8, left + 14, top + 10, cross);
        fill.draw(left + 8, top + 4, left + 10, top + 8, cross);
        fill.draw(left + 8, top + 10, left + 10, top + 14, cross);
    }

    @FunctionalInterface
    interface PixelFill {
        void draw(int left, int top, int right, int bottom, int color);
    }
}
