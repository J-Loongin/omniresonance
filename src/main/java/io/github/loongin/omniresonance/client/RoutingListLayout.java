// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Shared fixed-window geometry for terminal and node routing lists, including their scroll range. */
record RoutingListLayout(
        TerminalLayout.Rect rows, TerminalLayout.Rect scrollbar, int visibleRows, int scroll, int totalRows) {
    static final int ROW_HEIGHT = 20;
    static final int ROW_STRIDE = ROW_HEIGHT + 4;
    static final int HEADING_HEIGHT = TerminalHeaderLayout.HEIGHT + 2;

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
        int visibleRows = capacity(body.height(), ROW_HEIGHT, ROW_STRIDE - ROW_HEIGHT);
        int height = occupiedHeight(body.height(), ROW_HEIGHT, ROW_STRIDE - ROW_HEIGHT);
        int rowsWidth = TerminalLayout.reservedScrollContentWidth(body.width(), 4);
        TerminalLayout.Rect rows =
                new TerminalLayout.Rect(Math.min(body.right(), body.x() + 4), body.y(), rowsWidth, height);
        TerminalLayout.Rect scrollbar = new TerminalLayout.Rect(
                Math.max(body.x(), body.right() - TerminalLayout.SCROLLBAR_WIDTH - 2),
                rows.y(),
                Math.min(TerminalLayout.SCROLLBAR_WIDTH, body.width()),
                rows.height());
        int scroll = Math.max(0, Math.min(requestedScroll, Math.max(0, entryCount - visibleRows)));
        return new RoutingListLayout(rows, scrollbar, visibleRows, scroll, entryCount);
    }

    /** Full rows require gaps only between neighbors. Retains one clipped row for a sub-control-sized viewport. */
    static int capacity(int available, int rowHeight, int gap) {
        if (available < 0 || rowHeight <= 0 || gap < 0) throw new IllegalArgumentException("Invalid row geometry");
        int stride = Math.addExact(rowHeight, gap);
        return available < rowHeight ? 1 : 1 + (available - rowHeight) / stride;
    }

    static int occupiedHeight(int available, int rowHeight, int gap) {
        int count = capacity(available, rowHeight, gap);
        return (int) Math.min(available, (long) (count - 1) * (rowHeight + gap) + rowHeight);
    }

    /** Wheel/ghost viewport includes the scrollbar lane while keeping unused vertical space out of the list. */
    TerminalLayout.Rect bounds() {
        return new TerminalLayout.Rect(
                rows.x(), rows.y(), Math.max(0, Math.max(rows.right(), scrollbar.right()) - rows.x()), rows.height());
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
