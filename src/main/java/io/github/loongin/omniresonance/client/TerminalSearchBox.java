// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Shared search-only right-click clearing; ordinary text editors keep their vanilla mouse behavior. */
final class TerminalSearchBox extends TerminalEditBox {
    /** Same horizontal geometry as a routing result row, including the permanent scrollbar reservation. */
    static TerminalLayout.Rect bounds(TerminalLayout.Rect panel, int y) {
        return new TerminalLayout.Rect(
                Math.min(panel.right(), panel.x() + 4),
                y,
                TerminalLayout.reservedScrollContentWidth(panel.width(), 4),
                20);
    }

    TerminalSearchBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (!active || !isVisible() || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        boolean wasFocused = isFocused();
        setFocused(true);
        // The supported EditBox predicate also checks editability; no private-field access is needed.
        if (!canConsumeInput()) {
            setFocused(wasFocused);
            return false;
        }
        if (!getValue().isEmpty()) {
            setValue("");
        }
        return true;
    }
}
