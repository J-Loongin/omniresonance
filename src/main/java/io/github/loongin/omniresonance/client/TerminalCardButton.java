// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Shared two-line card used by terminal modules and physical-node mode choices. */
final class TerminalCardButton extends TerminalClickButton {
    private final Component mark;
    private final Component description;

    TerminalCardButton(
            TerminalLayout.Rect bounds, Component mark, Component title, Component description, OnPress onPress) {
        super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), TerminalText.title(title), onPress);
        this.mark = TerminalText.body(mark);
        this.description = TerminalText.body(description);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int border = active && isHoveredOrFocused() ? TerminalTheme.FRAME_LINE : TerminalTheme.LINE;
        int background = !active
                ? TerminalTheme.RAISED_DISABLED
                : isHoveredOrFocused() ? TerminalTheme.RAISED_HOVERED : TerminalTheme.PANEL;
        TerminalTheme.fillRounded(
                graphics, getX(), getY(), getWidth(), getHeight(), TerminalTheme.PANEL_RADIUS, border);
        TerminalTheme.fillRounded(
                graphics,
                getX() + 1,
                getY() + 1,
                Math.max(0, getWidth() - 2),
                Math.max(0, getHeight() - 2),
                Math.max(0, TerminalTheme.PANEL_RADIUS - 1),
                background);
        if (active && isHoveredOrFocused()) {
            graphics.fillGradient(
                    getX() + 1, getY() + 5, getX() + 3, getBottom() - 5, TerminalTheme.ACCENT, TerminalTheme.VIOLET);
        }
        Font font = TerminalText.font(Minecraft.getInstance());
        int titleY = getY() + Math.max(5, getHeight() / 3 - 5);
        int textColor = active ? TerminalTheme.TEXT : TerminalTheme.MUTED;
        graphics.drawString(font, mark, getX() + 7, titleY, active ? TerminalTheme.ACCENT : TerminalTheme.MUTED, false);
        String title = TerminalText.ellipsize(
                getMessage().getString(),
                Math.max(0, getWidth() - 32),
                value -> font.width(TerminalText.title(Component.literal(value))));
        graphics.drawString(font, TerminalText.title(Component.literal(title)), getX() + 25, titleY, textColor, false);
        if (getHeight() >= 38) {
            graphics.drawString(
                    font,
                    TerminalText.ellipsize(font, description.getString(), Math.max(0, getWidth() - 14)),
                    getX() + 7,
                    Math.min(getY() + getHeight() - 13, titleY + 14),
                    TerminalTheme.MUTED,
                    false);
        }
    }
}
