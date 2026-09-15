// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/** Hover-only interval stepping; invalid drafts are preserved and never converted into guessed values. */
final class TerminalIntervalBox extends TerminalEditBox {
    private boolean wheelEditable = true;

    TerminalIntervalBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
    }

    @Override
    public void setEditable(boolean editable) {
        super.setEditable(editable);
        wheelEditable = editable;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!active
                || !isVisible()
                || !wheelEditable
                || !isMouseOver(mouseX, mouseY)
                || !Double.isFinite(scrollY)
                || scrollY == 0) return false;
        try {
            int value = Integer.parseInt(getValue().trim());
            if (value < 1) return true;
            int next = (int) Math.clamp((long) value + (scrollY > 0 ? 1 : -1), 1, Integer.MAX_VALUE);
            if (next != value) setValue(Integer.toString(next));
        } catch (NumberFormatException invalid) {
            // The user's partial/invalid input remains intact until explicitly edited.
        }
        return true;
    }
}
