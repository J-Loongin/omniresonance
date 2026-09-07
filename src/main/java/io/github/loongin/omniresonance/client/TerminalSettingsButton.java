// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Object-local settings affordance; its gear is drawn independently of font fallback glyphs. */
final class TerminalSettingsButton extends TerminalClickButton {
    private static final int ICON_SIZE = 12;
    private static final int[] GEAR_ROWS = {
        0b000011110000,
        0b011011110110,
        0b011111111110,
        0b001110011100,
        0b111100001111,
        0b111000000111,
        0b111000000111,
        0b111100001111,
        0b001110011100,
        0b011111111110,
        0b011011110110,
        0b000011110000
    };

    TerminalSettingsButton(TerminalLayout.Rect bounds, Component label, OnPress onPress) {
        super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), TerminalText.body(label), onPress);
        setTooltip(Tooltip.create(TerminalText.body(label)));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean highlighted = active && isHoveredOrFocused();
        if (highlighted) {
            TerminalTheme.fillRounded(graphics, getX(), getY(), getWidth(), getHeight(), 3, TerminalTheme.ICON_HOVERED);
        }
        if (getWidth() < ICON_SIZE || getHeight() < ICON_SIZE) {
            return;
        }
        int left = getX() + (getWidth() - ICON_SIZE) / 2;
        int top = getY() + (getHeight() - ICON_SIZE) / 2;
        int color = highlighted ? TerminalTheme.ACCENT : TerminalTheme.MUTED;
        for (int row = 0; row < ICON_SIZE; row++) {
            int column = 0;
            while (column < ICON_SIZE) {
                if ((GEAR_ROWS[row] & (1 << column)) == 0) {
                    column++;
                    continue;
                }
                int start = column++;
                while (column < ICON_SIZE && (GEAR_ROWS[row] & (1 << column)) != 0) {
                    column++;
                }
                graphics.fill(left + start, top + row, left + column, top + row + 1, color);
            }
        }
    }
}
