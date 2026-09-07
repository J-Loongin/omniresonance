// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TerminalSearchButtonTest {
    @Test
    void magnifierHasAnEmptyLensAndADiagonalHandleInsideTheSharedActionSlot() {
        int[][] pixels = render(20, 20, true, false);
        assertEquals(TerminalTheme.MUTED, pixels[4][6]);
        assertEquals(0, pixels[7][7]);
        assertEquals(TerminalTheme.MUTED, pixels[14][14]);
        for (int index = 0; index < 20; index++) {
            assertEquals(0, pixels[0][index]);
            assertEquals(0, pixels[19][index]);
            assertEquals(0, pixels[index][0]);
            assertEquals(0, pixels[index][19]);
        }
    }

    @Test
    void highlightedSearchUsesTheSharedAccentAndDisabledSearchNeverHighlights() {
        int[][] highlighted = render(20, 20, true, true);
        int[][] disabled = render(20, 20, false, true);
        assertEquals(TerminalTheme.ACCENT, highlighted[4][6]);
        assertEquals(TerminalTheme.LINE, disabled[4][6]);
    }

    @Test
    void undersizedTargetsDoNotDrawClippedSymbols() {
        int[][] pixels = render(10, 10, true, false);
        for (int[] row : pixels) {
            for (int pixel : row) {
                assertEquals(0, pixel);
            }
        }
    }

    private static int[][] render(int width, int height, boolean active, boolean highlighted) {
        int[][] pixels = new int[height][width];
        TerminalSearchButton.drawIcon(7, 11, width, height, active, highlighted, (left, top, right, bottom, color) -> {
            assertTrue(left >= 7 && right <= 7 + width);
            assertTrue(top >= 11 && bottom <= 11 + height);
            for (int y = top - 11; y < bottom - 11; y++) {
                for (int x = left - 7; x < right - 7; x++) {
                    pixels[y][x] = color;
                }
            }
        });
        return pixels;
    }
}
