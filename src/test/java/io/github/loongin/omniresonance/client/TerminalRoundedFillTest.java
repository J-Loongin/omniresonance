// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class TerminalRoundedFillTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 6})
    void translucentSurfacesApplyOpacityOnlyOncePerPixel(int radius) {
        int[][] draws = new int[20][80];
        TerminalTheme.fillRounded(5, 7, 80, 20, radius, TerminalTheme.LINE, (left, top, right, bottom, color) -> {
            assertTrue(left >= 5 && right <= 85 && top >= 7 && bottom <= 27);
            for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) draws[y - 7][x - 5]++;
        });
        for (int[] row : draws)
            for (int count : row)
                assertTrue(
                        count <= 1, "Overlapping rectangles amplify a translucent border beyond its specified opacity");
    }
}
