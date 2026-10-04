// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class TerminalDecorationTest {
    @Test
    void installedAtlasMatchesRuntimeSlicesAndKeepsContentAndPaddingEmpty() throws Exception {
        try (var stream =
                getClass().getResourceAsStream("/assets/omniresonance/textures/gui/star_fissure/nebula_edges.png")) {
            assertNotNull(stream);
            var image = ImageIO.read(stream);
            assertEquals(TerminalDecoration.WIDTH, image.getWidth());
            assertEquals(TerminalDecoration.HEIGHT, image.getHeight());
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = image.getRGB(x, y), alpha = pixel >>> 24;
                    org.junit.jupiter.api.Assertions.assertTrue(alpha <= 64);
                    if (alpha == 0) assertEquals(0, pixel);
                    if (x >= 1520 || y >= 920 || x >= 48 && x < 1472 && y >= 48 && y < 872) assertEquals(0, pixel);
                }
            for (int row = 0; row < 3; row++)
                for (int col = 0; col < 3; col++) {
                    if (row == 1 && col == 1) continue;
                    var source = TerminalDecoration.source(row, col);
                    org.junit.jupiter.api.Assertions.assertTrue(source.right() <= 1520 && source.bottom() <= 920);
                }
            assertThrows(IllegalArgumentException.class, () -> TerminalDecoration.source(1, 1));
        }
    }
}
