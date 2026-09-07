// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Local search toggle using the same fixed action slot and click feedback as the other UI icons. */
final class TerminalSearchButton extends TerminalClickButton {
    private static final int ICON_SIZE = 12;
    private static final int[] ICON_ROWS = {
        0b001111000000,
        0b011001100000,
        0b110000110000,
        0b100000010000,
        0b100000010000,
        0b110000110000,
        0b011001110000,
        0b001111111000,
        0b000000011100,
        0b000000001110,
        0b000000000111,
        0b000000000011
    };
    private final boolean expanded;

    TerminalSearchButton(TerminalLayout.Rect bounds, boolean expanded, Component label, OnPress onPress) {
        super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), TerminalText.body(label), onPress);
        this.expanded = expanded;
        setTooltip(Tooltip.create(TerminalText.body(label)));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean highlighted = expanded || isHoveredOrFocused();
        if (active && highlighted) {
            TerminalTheme.fillRounded(graphics, getX(), getY(), getWidth(), getHeight(), 3, TerminalTheme.ICON_HOVERED);
        }
        drawIcon(getX(), getY(), getWidth(), getHeight(), active, highlighted, graphics::fill);
    }

    static void drawIcon(
            int x,
            int y,
            int width,
            int height,
            boolean active,
            boolean highlighted,
            TerminalIconButton.PixelFill fill) {
        if (width < ICON_SIZE || height < ICON_SIZE) {
            return;
        }
        int left = x + (width - ICON_SIZE) / 2;
        int top = y + (height - ICON_SIZE) / 2;
        int color = !active ? TerminalTheme.LINE : highlighted ? TerminalTheme.ACCENT : TerminalTheme.MUTED;
        for (int row = 0; row < ICON_SIZE; row++) {
            for (int column = 0; column < ICON_SIZE; column++) {
                if ((ICON_ROWS[row] & (1 << (ICON_SIZE - 1 - column))) != 0) {
                    fill.draw(left + column, top + row, left + column + 1, top + row + 1, color);
                }
            }
        }
    }
}
