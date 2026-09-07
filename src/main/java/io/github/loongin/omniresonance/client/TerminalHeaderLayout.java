// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Fixed top-bar actions and full-width content titles shared by terminal and node screens. */
final class TerminalHeaderLayout {
    static final int HEIGHT = 20;
    private static final int INSET = 4;

    private TerminalHeaderLayout() {}

    static TerminalLayout.Rect topBarContent(TerminalLayout.Rect window) {
        return new TerminalLayout.Rect(
                Math.min(window.right(), window.x() + INSET),
                Math.min(window.bottom(), window.y() + INSET),
                Math.max(0, window.width() - INSET * 2),
                Math.min(HEIGHT, Math.max(0, window.height() - INSET * 2)));
    }

    /** Reserves the far-right action before arranging network, status and other controls to its left. */
    static ActionLayout atRightEdge(TerminalLayout.Rect available, boolean hasAction) {
        int size = hasAction ? Math.min(HEIGHT, Math.min(available.width(), available.height())) : 0;
        int gap = hasAction ? Math.min(TerminalLayout.GAP, Math.max(0, available.width() - size)) : 0;
        return new ActionLayout(
                new TerminalLayout.Rect(
                        available.x(), available.y(), available.width() - size - gap, available.height()),
                new TerminalLayout.Rect(
                        available.right() - size, available.y() + (available.height() - size) / 2, size, size));
    }

    static TerminalLayout.Rect contentTitle(TerminalLayout.Rect content) {
        return new TerminalLayout.Rect(
                Math.min(content.right(), content.x() + INSET),
                content.y(),
                Math.max(0, content.width() - INSET * 2),
                Math.min(HEIGHT, content.height()));
    }

    enum Action {
        NONE,
        SEARCH,
        CREATE,
        SETTINGS
    }

    record ActionLayout(TerminalLayout.Rect remaining, TerminalLayout.Rect action) {}
}
