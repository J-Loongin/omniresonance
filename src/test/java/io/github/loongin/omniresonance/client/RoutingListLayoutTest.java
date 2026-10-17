// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class RoutingListLayoutTest {
    @Test
    void aFinalRowNeedsNoTrailingGapAndScrollbarEndsAtTheLastRow() {
        var rows = RoutingListLayout.calculateRows(new TerminalLayout.Rect(0, 0, 200, 142), 7, 99);
        assertEquals(6, rows.visibleRows());
        assertEquals(1, rows.scroll());
        assertEquals(140, rows.row(5).bottom());
        assertEquals(rows.row(5).bottom(), rows.scrollbar().bottom());
    }

    @Test
    void standardTerminalRoutingUsesSevenCompleteRows() {
        var body = TerminalLayout.terminal(640, 360).content();
        var rows = RoutingListLayout.calculate(body, 20, 99);
        assertEquals(7, rows.visibleRows());
        assertEquals(13, rows.scroll());
        assertTrue(rows.row(6).bottom() <= body.bottom());
    }

    @Test
    void sharedRowsLeaveRoomForTheHeaderAndUseTheSameStrideAsScrolling() {
        RoutingListLayout layout = RoutingListLayout.calculate(new TerminalLayout.Rect(10, 20, 200, 150), 100, 999);

        assertEquals(new TerminalLayout.Rect(14, 42, 186, 116), layout.rows());
        assertEquals(new TerminalLayout.Rect(14, 42, 186, 20), layout.row(0));
        assertEquals(new TerminalLayout.Rect(14, 66, 186, 20), layout.row(1));
        assertEquals(5, layout.visibleRows());
        assertEquals(95, layout.scroll());
        assertEquals(layout.rows().y(), layout.scrollbar().y());
        assertEquals(layout.rows().height(), layout.scrollbar().height());
    }

    @Test
    void allHeightBoundariesUseEveryFittingCompleteRowAndKeepTheWheelLane() {
        for (int height = 0; height < 400; height++) {
            var rows = RoutingListLayout.calculateRows(new TerminalLayout.Rect(0, 0, 200, height), 100, 999);
            assertTrue(rows.row(rows.visibleRows() - 1).bottom() <= height);
            assertEquals(
                    rows.row(rows.visibleRows() - 1).bottom(), rows.scrollbar().bottom());
            assertTrue(rows.bounds().right() >= rows.scrollbar().right());
            if (height >= 20) assertTrue(rows.visibleRows() * 24 + 20 > height, "Another complete row was wasted");
            assertEquals(100, rows.totalRows());
        }
    }

    @ParameterizedTest
    @CsvSource({"320,240", "427,240", "640,360", "1920,1080"})
    void contentTitleAndRowsFitWithoutChangingWidthWhenScrolling(int width, int height) {
        TerminalLayout.Rect content = TerminalLayout.calculate(width, height).content();
        RoutingListLayout shortList = RoutingListLayout.calculate(content, 1, 0);
        RoutingListLayout longList = RoutingListLayout.calculate(content, 100, 999);
        TerminalLayout.Rect title = TerminalHeaderLayout.contentTitle(content);

        assertEquals(shortList.rows(), longList.rows());
        assertEquals(title.x(), longList.rows().x());
        assertEquals(title.bottom() + 2, longList.row(0).y());
        assertEquals(content.width() - 8, title.width());
        assertTrue(longList.row(longList.visibleRows() - 1).bottom() <= content.bottom());
        assertTrue(longList.rows().right() <= longList.scrollbar().x());
    }
}
