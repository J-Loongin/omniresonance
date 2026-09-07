// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Left-aligned management row with a quiet surface and a scoped focus/selection marker. */
final class TerminalRowButton extends TerminalClickButton {
    private boolean selected;
    private boolean readOnly;

    TerminalRowButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, TerminalText.body(message), onPress);
    }

    TerminalRowButton(TerminalLayout.Rect bounds, Component message, OnPress onPress) {
        this(bounds.x(), bounds.y(), bounds.width(), bounds.height(), message, onPress);
    }

    /** Uses the native inactive-input path without presenting readable information as disabled. */
    void setReadOnly() {
        readOnly = true;
        active = false;
    }

    int textColor() {
        return active || readOnly ? TerminalTheme.TEXT : TerminalTheme.MUTED;
    }

    boolean highlighted() {
        return !readOnly && active && (selected || isHoveredOrFocused());
    }

    void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean highlighted = highlighted();
        TerminalTheme.fillRounded(
                graphics,
                getX(),
                getY(),
                getWidth(),
                getHeight(),
                TerminalTheme.BUTTON_RADIUS,
                highlighted ? TerminalTheme.FRAME_LINE : TerminalTheme.LINE);
        TerminalTheme.fillRounded(
                graphics,
                getX() + 1,
                getY() + 1,
                Math.max(0, getWidth() - 2),
                Math.max(0, getHeight() - 2),
                TerminalTheme.BUTTON_RADIUS - 1,
                highlighted ? TerminalTheme.ROW_HOVERED : TerminalTheme.ROW);
        if (highlighted) {
            graphics.fillGradient(
                    getX() + 1, getY() + 4, getX() + 3, getBottom() - 4, TerminalTheme.ACCENT, TerminalTheme.VIOLET);
        }
        Font font = TerminalText.font(Minecraft.getInstance());
        String text = TerminalText.ellipsize(font, getMessage().getString(), Math.max(0, getWidth() - 14));
        graphics.drawString(font, text, getX() + 7, getY() + (getHeight() - 8) / 2, textColor(), false);
    }
}
