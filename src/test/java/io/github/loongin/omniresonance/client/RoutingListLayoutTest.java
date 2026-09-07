// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class RoutingListLayoutTest {
    @Test
    void sharedRowsLeaveRoomForTheHeaderAndUseTheSameStrideAsScrolling() {
        RoutingListLayout layout = RoutingListLayout.calculate(new TerminalLayout.Rect(10, 20, 200, 150), 100, 999);

        assertEquals(new TerminalLayout.Rect(14, 46, 186, 124), layout.rows());
        assertEquals(new TerminalLayout.Rect(14, 46, 186, 20), layout.row(0));
        assertEquals(new TerminalLayout.Rect(14, 70, 186, 20), layout.row(1));
        assertEquals(5, layout.visibleRows());
        assertEquals(95, layout.scroll());
        assertEquals(layout.rows().y(), layout.scrollbar().y());
        assertEquals(layout.rows().height(), layout.scrollbar().height());
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
        assertEquals(title.bottom() + 6, longList.row(0).y());
        assertEquals(content.width() - 8, title.width());
        assertTrue(longList.row(longList.visibleRows() - 1).bottom() <= content.bottom());
        assertTrue(longList.rows().right() <= longList.scrollbar().x());
    }
}
