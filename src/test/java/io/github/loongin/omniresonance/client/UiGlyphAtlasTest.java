// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class UiGlyphAtlasTest {
    @Test
    void installedHomeAndSharedSymbolsHaveActualFourTimesSourcesWithTransparentGutters() throws Exception {
        try (var stream =
                getClass().getResourceAsStream("/assets/omniresonance/textures/gui/star_fissure/symbols.png")) {
            assertNotNull(stream, "The four-times GUI package must include the home and shared symbols");
            var image = ImageIO.read(stream);
            assertEquals(1024, image.getWidth());
            assertEquals(512, image.getHeight());
            for (int i = 0; i < 9; i++) {
                int x = i % 8 * 96 + 8, y = i / 8 * 96 + 8, coverage = 0, partial = 0;
                for (int dy = 0; dy < 80; dy++)
                    for (int dx = 0; dx < 80; dx++) {
                        int alpha = image.getRGB(x + dx, y + dy) >>> 24;
                        if (alpha > 0) coverage++;
                        if (alpha > 0 && alpha < 255) partial++;
                    }
                assertTrue(coverage > 250, "Each home/mode symbol needs a visible contour");
                assertTrue(partial > 10, "Contour edges must come from refined source geometry");
            }
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = image.getRGB(x, y);
                    if (pixel >>> 24 == 0) assertEquals(0, pixel);
                    if (x % 96 < 8 || y % 96 < 8 || x >= 768 || y >= 288) assertEquals(0, pixel);
                }
        }
    }
}
