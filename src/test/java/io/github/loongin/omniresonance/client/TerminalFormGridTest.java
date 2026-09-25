// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminalFormGridTest {
    @Test
    void spanningFieldsUseTheSameColumnsAndFillTheLastEdge() {
        for (int width : new int[] {200, 340, 364}) {
            var row = new TerminalLayout.Rect(12, 20, width, 32);
            var first = TerminalFormGrid.control(row, 3, 0, 2);
            var last = TerminalFormGrid.control(row, 3, 2, 1);
            assertEquals(row.x(), first.x());
            assertEquals(first.right() + 6, last.x());
            assertEquals(row.right(), last.right());
            assertEquals(20, first.height());
            assertEquals(row.y() + 10, first.y());
            assertTrue(first.bottom() <= row.bottom());
        }
    }

    @Test
    void exchangeIntervalPreservesPartialInputAndUsesTheSharedWheelBehavior() {
        var font =
                new net.minecraft.client.gui.Font(
                        id -> {
                            throw new AssertionError("No rendering");
                        },
                        false) {
                    @Override
                    public int width(String value) {
                        return value.length();
                    }

                    @Override
                    public String plainSubstrByWidth(String value, int width) {
                        return value.substring(0, Math.min(value.length(), Math.max(0, width)));
                    }

                    @Override
                    public String plainSubstrByWidth(String value, int width, boolean reverse) {
                        return plainSubstrByWidth(value, width);
                    }
                };
        var changed = new java.util.concurrent.atomic.AtomicReference<String>();
        var box =
                ExchangeScreen.intervalControl(font, new TerminalLayout.Rect(10, 20, 100, 20), "3", true, changed::set);
        assertTrue(box.mouseScrolled(11, 21, 0, 1));
        assertEquals("4", changed.get());
        box.setValue("");
        box.mouseScrolled(11, 21, 0, 1);
        assertEquals("", box.getValue());
        box.setValue("2147483647");
        box.mouseScrolled(11, 21, 0, 1);
        assertEquals("2147483647", box.getValue());
        var locked = ExchangeScreen.intervalControl(
                font, new TerminalLayout.Rect(10, 20, 100, 20), "3", false, changed::set);
        org.junit.jupiter.api.Assertions.assertFalse(locked.mouseScrolled(11, 21, 0, 1));
        assertEquals("3", locked.getValue());
    }

    @Test
    void exchangeDetailFitsThreeCommonRowsWithoutAReadOnlySaveFooter() {
        for (var size : new int[][] {{640, 360}, {960, 540}, {1920, 1080}}) {
            var body = TerminalLayout.terminal(size[0], size[1]).content();
            var rows = ExchangeScreen.detailContent(body, body.y() + 26);
            assertTrue(rows.height() >= 3 * 32);
            assertTrue(rows.y() > body.y() + 26);
            assertEquals(body.bottom(), rows.bottom());
            var first = TerminalFormGrid.control(
                    new TerminalLayout.Rect(rows.x(), rows.y(), rows.width() - 6, 32), 3, 0, 2);
            var last = TerminalFormGrid.control(
                    new TerminalLayout.Rect(rows.x(), rows.y(), rows.width() - 6, 32), 3, 2, 1);
            assertEquals(first.right() + 6, last.x());
        }
    }
}
