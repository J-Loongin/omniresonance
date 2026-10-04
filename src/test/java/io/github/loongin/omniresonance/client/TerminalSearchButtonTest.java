// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class TerminalSearchButtonTest {
    @Test
    void installedMagnifierHasAnEmptyLensAndDiagonalHandleAtItsTrueFourTimesResolution() throws Exception {
        try (var stream =
                getClass().getResourceAsStream("/assets/omniresonance/textures/gui/star_fissure/symbols.png")) {
            var image = ImageIO.read(stream);
            var source =
                    TerminalActionIcon.glyph(TerminalActionIcon.Symbol.SEARCH).source();
            assertEquals(48, source.width());
            assertEquals(0, image.getRGB(source.x() + 18, source.y() + 18) >>> 24);
            assertTrue((image.getRGB(source.x() + 18, source.y() + 2) >>> 24) > 0);
            assertTrue((image.getRGB(source.x() + 40, source.y() + 40) >>> 24) > 0);
        }
    }

    @Test
    void expandedSearchStaysSelectedAndDisabledSearchNeverHighlights() {
        assertEquals(
                TerminalTheme.ACCENT,
                TerminalActionIcon.style(true, false, false, true).border());
        assertEquals(
                TerminalTheme.DISABLED_TEXT,
                TerminalActionIcon.style(false, true, false, true).text());
    }
}
