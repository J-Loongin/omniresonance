// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalTagPopupTest {
    @Test
    void compactMenuFitsItsTextAndOnlyReservesScrollbarSpaceWhenNeeded() {
        var tags = List.of("c:buckets", "c:buckets/lava");
        var popup = new TerminalTagPopup(
                tags, new TerminalLayout.Rect(80, 80, 18, 18), 427, 240, value -> value.length() * 6);
        assertEquals(
                (int) Math.ceil("#c:buckets/lava".length() * 6 * 0.8) + 8,
                popup.bounds().width());
        assertEquals(28, popup.bounds().height());
        assertEquals(
                "c:buckets/lava",
                popup.tagAt(popup.bounds().x() + 6, popup.bounds().y() + 18));
        var six = new TerminalTagPopup(
                List.of("c:a", "c:b", "c:c", "c:d", "c:e", "c:f"),
                new TerminalLayout.Rect(80, 80, 18, 18),
                427,
                240,
                value -> value.length() * 6);
        assertEquals(34, six.bounds().width());
        assertEquals(64, six.bounds().height());
    }

    @Test
    void outsideClickOnlyDismissesAndInsideLeftClickCopiesExactlyOneTag() {
        var popup = new TerminalTagPopup(
                List.of("c:a", "c:b"), new TerminalLayout.Rect(20, 20, 18, 18), 320, 240, String::length);
        var box = popup.bounds();
        assertEquals(new TerminalTagPopup.Click(true, null), popup.click(0, 0, 0));
        assertEquals(new TerminalTagPopup.Click(true, "c:a"), popup.click(box.x() + 5, box.y() + 5, 0));
        assertEquals(new TerminalTagPopup.Click(false, null), popup.click(box.x() + 5, box.y() + 5, 1));
    }

    @Test
    void staysBesideTheResourceInsideScreenAndScrollsFiveSingleChoices() {
        var popup = new TerminalTagPopup(
                List.of("c:f", "c:e", "c:d", "c:c", "c:b", "c:a"),
                new TerminalLayout.Rect(290, 210, 18, 18),
                320,
                240,
                String::length);
        var box = popup.bounds();
        assertTrue(box.x() >= 4 && box.right() <= 316 && box.y() >= 4 && box.bottom() <= 236);
        assertTrue(box.right() < 290);
        assertEquals(5, popup.rows());
        assertEquals("c:a", popup.tagAt(box.x() + 5, box.y() + 5));
        assertNull(popup.tagAt(box.x() - 1, box.y() + 5));
        popup.scroll(-1);
        assertEquals("c:b", popup.tagAt(box.x() + 5, box.y() + 5));
        assertEquals("c:f", popup.tagAt(box.x() + 5, box.y() + 53));
        popup.scroll(-100);
        assertEquals("c:f", popup.tagAt(box.x() + 5, box.y() + 53));
    }
}
