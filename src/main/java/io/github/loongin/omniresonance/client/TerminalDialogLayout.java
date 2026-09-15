// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/** Content-sized local dialogs inside the stable outer window, sharing footer action geometry. */
final class TerminalDialogLayout {
    private TerminalDialogLayout() {}

    static void render(
            net.minecraft.client.gui.GuiGraphics graphics, TerminalLayout.Rect parent, TerminalLayout.Rect dialog) {
        graphics.fill(parent.x(), parent.y(), parent.right(), parent.bottom(), 0x88000000);
        TerminalTheme.renderPanel(graphics, dialog);
    }

    static TerminalLayout.Rect editor(TerminalLayout.Rect parent) {
        return centered(parent, 300, 124);
    }

    static TerminalLayout.Rect confirmation(TerminalLayout.Rect parent, Font font, Component message) {
        int width = Math.min(260, Math.max(0, parent.width() - 16));
        int lines =
                font.split(TerminalText.body(message), Math.max(1, width - 20)).size();
        return confirmation(parent, lines);
    }

    static TerminalLayout.Rect confirmation(TerminalLayout.Rect parent, int lines) {
        if (lines < 0) throw new IllegalArgumentException("Negative line count");
        return centered(parent, 260, 68 + Math.max(1, Math.min(lines, 1000)) * 10);
    }

    static TerminalLayout.Rect centered(TerminalLayout.Rect parent, int preferredWidth, int preferredHeight) {
        int width = Math.min(preferredWidth, Math.max(0, parent.width() - 16));
        int height = Math.min(preferredHeight, Math.max(0, parent.height() - 8));
        return new TerminalLayout.Rect(
                parent.x() + (parent.width() - width) / 2, parent.y() + (parent.height() - height) / 2, width, height);
    }
}
