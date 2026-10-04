// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** A native action target with an atlas symbol; readable help retains the inactive input path. */
final class TerminalSymbolButton extends TerminalClickButton {
    private final TerminalGlyph glyph;
    private boolean readOnly;

    TerminalSymbolButton(TerminalLayout.Rect bounds, Component message, TerminalGlyph glyph, OnPress action) {
        super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), TerminalText.body(message), action);
        this.glyph = glyph;
    }

    void setReadOnly() {
        readOnly = true;
        active = false;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var style = TerminalTheme.controlStyle(
                active || readOnly, active && isHovered, active && isFocused(), false, false, false);
        TerminalTheme.renderControl(graphics, new TerminalLayout.Rect(getX(), getY(), getWidth(), getHeight()), style);
        glyph.render(graphics, getX() + (getWidth() - 12) / 2, getY() + (getHeight() - 12) / 2, style.text());
    }
}
