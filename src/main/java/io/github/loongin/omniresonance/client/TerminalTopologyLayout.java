// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Pure fixed-window geometry for terminal topology views. */
final class TerminalTopologyLayout {
    private TerminalTopologyLayout() {}

    static Root root(TerminalLayout.Rect content) {
        int actionWidth = Math.min(320, Math.max(0, content.width() - 24));
        int actionOffset = Math.min(116, Math.max(80, content.height() / 3));
        TerminalLayout.Rect action = new TerminalLayout.Rect(
                content.x() + (content.width() - actionWidth) / 2,
                Math.min(content.bottom() - 20, content.y() + actionOffset),
                actionWidth,
                20);
        TerminalLayout.Rect description = new TerminalLayout.Rect(
                content.x() + 12, Math.max(content.y(), action.y() - 30), Math.max(0, content.width() - 24), 14);
        return new Root(description, action);
    }

    record Root(TerminalLayout.Rect description, TerminalLayout.Rect action) {}
}
