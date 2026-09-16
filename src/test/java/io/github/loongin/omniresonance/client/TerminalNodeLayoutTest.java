// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminalNodeLayoutTest {
    @Test
    void searchOnlyConsumesTheLeftPaneAndNeverMovesTheDetailPane() {
        var body = new TerminalLayout.Rect(8, 36, 368, 172);
        var closed = TerminalNodeLayout.calculate(body, false);
        var open = TerminalNodeLayout.calculate(body, true);
        assertEquals(closed.detail(), open.detail());
        assertEquals(0, closed.toolbar().height());
        assertEquals(26, open.list().y() - closed.list().y());
        assertEquals(open.list().width(), open.toolbar().width());
    }

    @Test
    void wideLayoutUsesPrototypeRatioAndIndependentContentBelowToolbar() {
        var area = new TerminalLayout.Rect(10, 20, 700, 360);
        var layout = TerminalNodeLayout.calculate(area, false);
        assertEquals((700 - 6) * 38 / 100, layout.list().width());
        assertEquals(layout.list().right() + 6, layout.detail().x());
        assertEquals(area.right(), layout.detail().right());
        assertEquals(area.y(), layout.list().y());
        assertEquals(area.y(), layout.detail().y());
    }

    @Test
    void narrowLayoutKeepsListLeftAndDetailRight() {
        var layout = TerminalNodeLayout.calculate(new TerminalLayout.Rect(0, 0, 288, 172), false);
        assertTrue(layout.list().right() < layout.detail().x());
        assertEquals(288, layout.detail().right());
        assertTrue(layout.detail().width() > layout.list().width());
    }
}
