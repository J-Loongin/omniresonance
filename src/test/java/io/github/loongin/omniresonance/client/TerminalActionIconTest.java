// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TerminalActionIconTest {
    @Test
    void creationSearchAndSettingsUseTheSameFrameAndTrueFourTimesAtlasRegions() {
        for (var symbol : TerminalActionIcon.Symbol.values()) {
            assertEquals(new TerminalLayout.Rect(1, 1, 18, 18), TerminalActionIcon.bounds(0, 0, 20, 20));
            assertEquals(
                    TerminalTheme.LINE,
                    TerminalActionIcon.style(true, false, false, false).border());
            var source = TerminalActionIcon.glyph(symbol).source();
            assertEquals(48, source.width());
            assertEquals(48, source.height());
        }
    }

    @Test
    void everyHomeAndModeIdentityUsesAnEightyPixelSourceRatherThanAnUpscaledTwentyPixelMask() {
        for (var icon : TerminalModuleIcon.values()) {
            var source = icon.glyph().source();
            assertEquals(80, source.width());
            assertEquals(80, source.height());
            assertTrue(source.right() <= TerminalGlyph.ATLAS_WIDTH && source.bottom() <= TerminalGlyph.ATLAS_HEIGHT);
        }
        for (String module : TerminalHomeLayout.MODULES)
            assertEquals(
                    module, TerminalModuleIcon.forModule(module).glyph().name().toLowerCase(java.util.Locale.ROOT));
    }
}
