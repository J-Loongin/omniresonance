// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminalActionLayoutTest {
    @Test
    void editorsAndDialogsKeepCompactOrderedActionsAboveTheirBottomInset() {
        for (int width : new int[] {180, 248, 288, 368, 704}) {
            for (int height : new int[] {100, 172, 376}) {
                var bounds = new TerminalLayout.Rect(13, 27, width, height);
                var layout = TerminalActionLayout.of(bounds);
                assertEquals(bounds.right() - 8, layout.primary().right());
                assertEquals(bounds.bottom() - 8, layout.primary().bottom());
                assertEquals(layout.primary().y(), layout.secondary().y());
                assertEquals(layout.secondary().right() + 6, layout.primary().x());
                assertEquals(layout.primary().width(), layout.secondary().width());
                assertTrue(layout.primary().width() <= 80);
                assertTrue(layout.content().bottom() + 6 <= layout.primary().y());
                for (int count = 1; count <= 3; count++) {
                    for (int index = 0; index < count; index++) {
                        var slot = TerminalActionLayout.button(bounds, count, index);
                        assertTrue(slot.x() >= bounds.x() + 8);
                        assertTrue(slot.right() <= bounds.right() - 8);
                        assertEquals(bounds.bottom() - 8, slot.bottom());
                        if (index == count - 1) assertEquals(bounds.right() - 8, slot.right());
                    }
                }
            }
        }
    }
}
