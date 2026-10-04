// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Shared parameter-dialog geometry; owners supply rate semantics and optional input-only batch controls. */
record TerminalResourceParameterLayout(
        TerminalLayout.Rect dialog,
        TerminalLayout.Rect rate,
        TerminalLayout.Rect mode,
        TerminalLayout.Rect batch,
        TerminalLayout.Rect hint) {
    static TerminalResourceParameterLayout of(TerminalLayout.Rect parent, boolean batches, boolean unavailable) {
        var d = TerminalDialogLayout.centered(parent, 300, unavailable ? 110 : batches ? 156 : 124);
        int half = Math.max(0, (d.width() - 30) / 2);
        return new TerminalResourceParameterLayout(
                d,
                new TerminalLayout.Rect(d.x() + 12, d.y() + 42, Math.max(0, d.width() - 24), 20),
                new TerminalLayout.Rect(d.x() + 12, d.y() + 80, half, 20),
                new TerminalLayout.Rect(d.x() + 18 + half, d.y() + 80, half, 20),
                new TerminalLayout.Rect(d.x() + 12, d.bottom() - 46, Math.max(0, d.width() - 24), 10));
    }

    void renderHint(GuiGraphics graphics, Font font, Component message) {
        NodeResourcePolicyView.label(graphics, font, hint.x(), hint.y(), hint.width(), message);
    }
}
