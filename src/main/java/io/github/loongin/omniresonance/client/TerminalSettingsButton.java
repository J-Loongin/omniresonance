// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Object-local settings affordance; its gear is drawn independently of font fallback glyphs. */
final class TerminalSettingsButton extends TerminalClickButton {
    TerminalSettingsButton(TerminalLayout.Rect bounds, Component label, OnPress onPress) {
        super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), TerminalText.body(label), onPress);
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
                TerminalActionIcon.Symbol.SETTINGS);
    }
}
