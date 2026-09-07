// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Shared fixed-window geometry for terminal and node routing lists, including their scroll range. */
record RoutingListLayout(TerminalLayout.Rect rows, TerminalLayout.Rect scrollbar, int visibleRows, int scroll) {
    static final int ROW_HEIGHT = 20;
    static final int ROW_STRIDE = ROW_HEIGHT + 4;
    static final int HEADING_HEIGHT = TerminalHeaderLayout.HEIGHT + TerminalLayout.GAP;

    static RoutingListLayout calculate(TerminalLayout.Rect body, int entryCount, int requestedScroll) {
        int headingHeight = Math.min(HEADING_HEIGHT, body.height());
        return calculateRows(
                new TerminalLayout.Rect(
                        body.x(), body.y() + headingHeight, body.width(), body.height() - headingHeight),
                entryCount,
                requestedScroll);
    }

    /** Uses an already reserved content area without inserting another heading. */
    static RoutingListLayout calculateRows(TerminalLayout.Rect body, int entryCount, int requestedScroll) {
        if (entryCount < 0) {
            throw new IllegalArgumentException("Routing entry count must be nonnegative");
        }
        int rowsWidth = TerminalLayout.reservedScrollContentWidth(body.width(), 4);
        TerminalLayout.Rect rows =
                new TerminalLayout.Rect(Math.min(body.right(), body.x() + 4), body.y(), rowsWidth, body.height());
        TerminalLayout.Rect scrollbar = new TerminalLayout.Rect(
                Math.max(body.x(), body.right() - TerminalLayout.SCROLLBAR_WIDTH - 2),
                rows.y(),
                Math.min(TerminalLayout.SCROLLBAR_WIDTH, body.width()),
                rows.height());
        int visibleRows = Math.max(1, rows.height() / ROW_STRIDE);
        int scroll = Math.max(0, Math.min(requestedScroll, Math.max(0, entryCount - visibleRows)));
        return new RoutingListLayout(rows, scrollbar, visibleRows, scroll);
    }

    TerminalLayout.Rect row(int visibleIndex) {
        if (visibleIndex < 0 || visibleIndex >= visibleRows) {
            throw new IllegalArgumentException("Row index is outside the visible list");
        }
        int offset = visibleIndex * ROW_STRIDE;
        return new TerminalLayout.Rect(
                rows.x(), rows.y() + offset, rows.width(), Math.min(ROW_HEIGHT, Math.max(0, rows.height() - offset)));
    }
}
