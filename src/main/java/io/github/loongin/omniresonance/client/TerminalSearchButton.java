// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Local search toggle using the same fixed action slot and click feedback as the other UI icons. */
final class TerminalSearchButton extends TerminalClickButton {
    private final boolean expanded;

    TerminalSearchButton(TerminalLayout.Rect bounds, boolean expanded, Component label, OnPress onPress) {
        super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), TerminalText.body(label), onPress);
        this.expanded = expanded;
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
                expanded,
                TerminalActionIcon.Symbol.SEARCH);
    }
}
