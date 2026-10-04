// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Fixed top-bar actions and full-width content titles shared by terminal and node screens. */
final class TerminalHeaderLayout {
    static final int HEIGHT = 20;
    private static final int INSET = 4;
    private static final int TITLE_MARK_SPACE = 16;

    private TerminalHeaderLayout() {}

    static TerminalLayout.Rect topBarContent(TerminalLayout.Rect window) {
        return new TerminalLayout.Rect(
                Math.min(window.right(), window.x() + INSET + TITLE_MARK_SPACE),
                Math.min(window.bottom(), window.y() + INSET),
                Math.max(0, window.width() - INSET * 2 - TITLE_MARK_SPACE),
                Math.min(HEIGHT, Math.max(0, window.height() - INSET * 2)));
    }

    /** Reserves the far-right action before arranging network, status and other controls to its left. */
    static ActionLayout atRightEdge(TerminalLayout.Rect available, boolean hasAction) {
        int size = Math.min(HEIGHT, Math.min(available.width(), available.height()));
        int gap = Math.min(TerminalLayout.GAP, Math.max(0, available.width() - size));
        return new ActionLayout(
                new TerminalLayout.Rect(
                        available.x(), available.y(), available.width() - size - gap, available.height()),
                new TerminalLayout.Rect(
                        available.right() - size,
                        available.y() + (available.height() - size) / 2,
                        hasAction ? size : 0,
                        hasAction ? size : 0));
    }

    static NodeNames nodeNames(TerminalLayout.Rect remaining, boolean compact) {
        int right =
                remaining.right() - (compact ? 64 : 92) - TerminalLayout.GAP - (compact ? 52 : 66) - TerminalLayout.GAP;
        int available = Math.max(0, right - remaining.x());
        int nameWidth = available * 54 / 100;
        return new NodeNames(
                new TerminalLayout.Rect(remaining.x(), remaining.y(), nameWidth, HEIGHT),
                new TerminalLayout.Rect(
                        remaining.x() + nameWidth + TerminalLayout.GAP,
                        remaining.y(),
                        Math.max(0, available - nameWidth - TerminalLayout.GAP),
                        HEIGHT));
    }

    record NodeNames(TerminalLayout.Rect node, TerminalLayout.Rect network) {}

    static NodeNames remoteNodeNames(TerminalLayout.Rect remaining) {
        var network = TerminalNetworkContext.layout(remaining, false, false);
        return new NodeNames(
                new TerminalLayout.Rect(
                        remaining.x(),
                        remaining.y(),
                        Math.max(0, network.x() - remaining.x() - TerminalLayout.GAP),
                        remaining.height()),
                network);
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
