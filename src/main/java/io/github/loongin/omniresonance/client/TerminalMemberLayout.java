// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Fixed-window member panes. Compact layouts display only one of the coincident list/detail rectangles. */
record TerminalMemberLayout(TerminalLayout.Rect list, TerminalLayout.Rect detail, boolean compact) {
    static TerminalMemberLayout calculate(TerminalLayout layout) {
        TerminalLayout.Rect content = layout.content();
        if (layout.compact()) return new TerminalMemberLayout(content, content, true);
        int listWidth = Math.max(0, (content.width() - TerminalLayout.GAP) * 35 / 100);
        return new TerminalMemberLayout(
                new TerminalLayout.Rect(content.x(), content.y(), listWidth, content.height()),
                new TerminalLayout.Rect(
                        content.x() + listWidth + TerminalLayout.GAP,
                        content.y(),
                        Math.max(0, content.width() - listWidth - TerminalLayout.GAP),
                        content.height()),
                false);
    }
}
