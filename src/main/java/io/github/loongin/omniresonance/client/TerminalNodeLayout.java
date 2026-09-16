// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Search occupies only the left pane; collapsed search reserves no content row. */
record TerminalNodeLayout(TerminalLayout.Rect toolbar, TerminalLayout.Rect list, TerminalLayout.Rect detail) {
    static TerminalNodeLayout calculate(TerminalLayout.Rect body, boolean search) {
        int width = (body.width() - 6) * 38 / 100, inset = search ? 26 : 0;
        return new TerminalNodeLayout(
                new TerminalLayout.Rect(body.x(), body.y(), width, inset),
                new TerminalLayout.Rect(body.x(), body.y() + inset, width, Math.max(0, body.height() - inset)),
                new TerminalLayout.Rect(body.x() + width + 6, body.y(), body.width() - width - 6, body.height()));
    }
}
