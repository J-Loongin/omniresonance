// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class NodeRoutingLayoutTest {
    @ParameterizedTest
    @CsvSource({"320,240", "427,239", "640,360", "1920,1080"})
    void tunnelRowsStartOneGapBelowSearchWithoutReservingAnotherHeading(int width, int height) {
        TerminalLayout.Rect body = TerminalLayout.calculate(width, height).content();
        TerminalLayout.Rect search = NodeRoutingView.tunnelSearchBounds(body);
        RoutingListLayout list = NodeRoutingView.tunnelList(body, true, 128, 999);

        assertEquals(6, list.row(0).y() - search.bottom());
        assertEquals(search.x(), list.row(0).x());
        assertEquals(search.width(), list.row(0).width());
        assertEquals(body.bottom(), list.rows().bottom());
        assertEquals(list.rows().y(), list.scrollbar().y());
        assertEquals(list.rows().height(), list.scrollbar().height());
        assertTrue(list.row(list.visibleRows() - 1).bottom() <= body.bottom());
        assertTrue(list.rows().right() <= list.scrollbar().x());
    }

    @Test
    void reclaimedHeadingSpaceFitsAnotherRowAndClampsScrollingToTheNewRange() {
        TerminalLayout.Rect body = new TerminalLayout.Rect(10, 20, 200, 150);
        RoutingListLayout list = NodeRoutingView.tunnelList(body, true, 100, 999);

        assertEquals(new TerminalLayout.Rect(14, 38, 186, 20), NodeRoutingView.tunnelSearchBounds(body));
        assertEquals(new TerminalLayout.Rect(14, 64, 186, 106), list.rows());
        assertEquals(new TerminalLayout.Rect(14, 136, 186, 20), list.row(3));
        assertEquals(4, list.visibleRows());
        assertEquals(96, list.scroll());
    }

    @Test
    void emptyAndLongTunnelResultsKeepSearchAndRowWidthStable() {
        TerminalLayout.Rect body = new TerminalLayout.Rect(10, 20, 200, 150);
        RoutingListLayout empty = NodeRoutingView.tunnelList(body, true, 0, 999);
        RoutingListLayout longList = NodeRoutingView.tunnelList(body, true, 128, 999);

        assertEquals(empty.rows(), longList.rows());
        assertEquals(empty.scrollbar(), longList.scrollbar());
        assertEquals(0, empty.scroll());
    }

    @Test
    void collapsedSearchLeavesOnlyTheNormalTitleGapAndReclaimsListHeight() {
        TerminalLayout.Rect body = new TerminalLayout.Rect(10, 20, 200, 150);
        RoutingListLayout collapsed = NodeRoutingView.tunnelList(body, false, 100, 999);
        RoutingListLayout expanded = NodeRoutingView.tunnelList(body, true, 100, 999);

        assertEquals(new TerminalLayout.Rect(14, 46, 186, 124), collapsed.rows());
        assertEquals(5, collapsed.visibleRows());
        assertEquals(95, collapsed.scroll());
        assertEquals(expanded.rows().width(), collapsed.rows().width());
        assertEquals(expanded.rows().bottom(), collapsed.rows().bottom());
    }
}
