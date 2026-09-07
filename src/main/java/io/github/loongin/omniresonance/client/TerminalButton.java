// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Terminal-themed button with a text state in addition to its shared colors. */
final class TerminalButton extends TerminalClickButton {
    private final boolean primary;
    private boolean selected;

    TerminalButton(int x, int y, int width, int height, Component message, OnPress onPress, boolean primary) {
        super(x, y, width, height, TerminalText.body(message), onPress);
        this.primary = primary;
    }

    void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int border = active && (selected || isHoveredOrFocused()) ? TerminalTheme.FRAME_LINE : TerminalTheme.LINE;
        int background;
        if (!active) {
            background = TerminalTheme.RAISED_DISABLED;
        } else if (selected || isHoveredOrFocused()) {
            background = primary ? TerminalTheme.ACCENT_SOFT : TerminalTheme.RAISED_HOVERED;
        } else {
            background = TerminalTheme.RAISED;
        }
        TerminalTheme.fillRounded(
                graphics, getX(), getY(), getWidth(), getHeight(), TerminalTheme.BUTTON_RADIUS, border);
        TerminalTheme.fillRounded(
                graphics,
                getX() + 1,
                getY() + 1,
                Math.max(0, getWidth() - 2),
                Math.max(0, getHeight() - 2),
                Math.max(0, TerminalTheme.BUTTON_RADIUS - 1),
                background);
        int color = active ? TerminalTheme.TEXT : TerminalTheme.MUTED;
        Font font = TerminalText.font(Minecraft.getInstance());
        graphics.drawString(
                font,
                getMessage(),
                getX() + (getWidth() - font.width(getMessage())) / 2,
                getY() + (getHeight() - 8) / 2,
                color,
                false);
    }
}
