// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Shared framed creation action, drawn on integer pixels independently of font fallback. */
final class TerminalIconButton extends TerminalClickButton {
    TerminalIconButton(int x, int y, int width, int height, Component label, OnPress onPress) {
        super(x, y, width, height, TerminalText.body(label), onPress);
        setTooltip(Tooltip.create(TerminalText.body(label)));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        TerminalActionIcon.render(
                graphics,
                getX(),
                getY(),
                getWidth(),
                getHeight(),
                active,
                isHovered,
                isFocused(),
                false,
                TerminalActionIcon.Symbol.ADD);
    }

    @FunctionalInterface
    interface PixelFill {
        void draw(int left, int top, int right, int bottom, int color);
    }
}
