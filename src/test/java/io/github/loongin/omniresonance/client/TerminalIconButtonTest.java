// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class TerminalIconButtonTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void circleAndCrossStayCenteredAndSymmetricAtIntegerGuiScales(int scale) {
        int[][] pixels = render(20, 20, scale, true, false);
        int size = 20 * scale;
        int crossPixels = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                assertEquals(pixels[y][x], pixels[y][size - x - 1], "Horizontal symmetry");
                assertEquals(pixels[y][x], pixels[size - y - 1][x], "Vertical symmetry");
                if (x < scale || y < scale || x >= size - scale || y >= size - scale) {
                    assertEquals(0, pixels[y][x], "The circle must leave the click target's outer inset empty");
                }
                int logicalX = x / scale;
                int logicalY = y / scale;
                boolean cross = (logicalX >= 5 && logicalX < 15 && logicalY >= 9 && logicalY < 11)
                        || (logicalY >= 5 && logicalY < 15 && logicalX >= 9 && logicalX < 11);
                assertEquals(cross, pixels[y][x] == TerminalTheme.MUTED, "Equal-width centered cross strokes");
                if (cross) {
                    crossPixels++;
                }
            }
        }
        assertEquals(36 * scale * scale, crossPixels);
    }

    @Test
    void hoverBrightensBothRingAndCrossWhileDisabledNeverHighlights() {
        int[][] idle = render(20, 20, 1, true, false);
        int[][] hovered = render(20, 20, 1, true, true);
        int[][] disabled = render(20, 20, 1, false, false);
        int[][] disabledHovered = render(20, 20, 1, false, true);

        assertEquals(TerminalTheme.ACCENT, hovered[1][10]);
        assertEquals(TerminalTheme.ACCENT, hovered[10][10]);
        assertTrue(brightness(hovered[1][10]) > brightness(idle[1][10]));
        assertTrue(brightness(hovered[10][10]) > brightness(idle[10][10]));
        assertTrue(brightness(hovered[4][10]) > brightness(idle[4][10]));
        assertTrue(brightness(disabled[10][10]) < brightness(idle[10][10]));
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 20; x++) {
                assertEquals(disabled[y][x], disabledHovered[y][x]);
            }
        }
    }

    @Test
    void largerClickTargetsCenterTheSameIconWithoutStretchingIt() {
        int[][] square = render(20, 20, 1, true, false);
        int[][] wide = render(26, 22, 1, true, false);
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 20; x++) {
                assertEquals(square[y][x], wide[y + 1][x + 3]);
            }
        }
    }

    @Test
    void undersizedBoundsNeverDrawOutsideTheWidget() {
        int[][] pixels = render(16, 16, 1, true, true);
        for (int[] row : pixels) {
            for (int pixel : row) {
                assertEquals(0, pixel);
            }
        }
    }

    private static int[][] render(int width, int height, int scale, boolean active, boolean highlighted) {
        int[][] pixels = new int[height * scale][width * scale];
        TerminalIconButton.drawIcon(7, 11, width, height, active, highlighted, (left, top, right, bottom, color) -> {
            assertTrue(left >= 7 && right <= 7 + width);
            assertTrue(top >= 11 && bottom <= 11 + height);
            for (int y = (top - 11) * scale; y < (bottom - 11) * scale; y++) {
                for (int x = (left - 7) * scale; x < (right - 7) * scale; x++) {
                    pixels[y][x] = color;
                }
            }
        });
        return pixels;
    }

    private static long brightness(int color) {
        return (long) (color >>> 24) * (((color >>> 16) & 255) + ((color >>> 8) & 255) + (color & 255));
    }
}
