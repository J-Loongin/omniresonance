// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

final class TerminalWindowOutlineTest {
    @Test
    void travellingPixelsFollowTheVisibleSilverFrameOnEveryEdge() {
        var rect = new TerminalLayout.Rect(100, 50, 380, 230);
        int cut = TerminalTheme.OUTER_RADIUS;
        int horizontal = rect.width() - cut * 2;
        int vertical = rect.height() - cut * 2;
        assertEquals(new TerminalWindowOutline.Point(106, 49), TerminalWindowOutline.point(rect, cut, 0));
        assertEquals(
                new TerminalWindowOutline.Point(480, 56), TerminalWindowOutline.point(rect, cut, horizontal + cut));
        assertEquals(
                new TerminalWindowOutline.Point(473, 280),
                TerminalWindowOutline.point(rect, cut, horizontal + vertical + cut * 2));
        assertEquals(
                new TerminalWindowOutline.Point(99, 273),
                TerminalWindowOutline.point(rect, cut, horizontal * 2 + vertical + cut * 3));
    }

    @Test
    void oneCircuitVisitsEveryBorderPixelOnceWithoutGoingThroughTheWindowInterior() {
        var rect = new TerminalLayout.Rect(30, 20, 220, 150);
        int cut = 6;
        var visited = new HashSet<TerminalWindowOutline.Point>();
        int length = (int) TerminalWindowOutline.length(rect, cut);
        for (int step = 0; step < length; step++) {
            var p = TerminalWindowOutline.point(rect, cut, step);
            assertTrue(visited.add(p), "Repeated pixels darken or thicken the moving edge");
            int row = (int) p.y() - rect.y();
            int innerInset = row < cut ? cut - row : row >= rect.height() - cut ? cut - (rect.height() - row - 1) : 0;
            assertTrue(
                    row < 0
                            || row >= rect.height()
                            || p.x() < rect.x() + innerInset
                            || p.x() >= rect.right() - innerInset,
                    "The flow must stay on the external silver stroke");
        }
        assertEquals(TerminalWindowOutline.point(rect, cut, 0), TerminalWindowOutline.point(rect, cut, length));
        assertEquals(TerminalWindowOutline.point(rect, cut, length - 1), TerminalWindowOutline.point(rect, cut, -1));
    }
}
