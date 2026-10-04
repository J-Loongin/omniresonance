// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class TerminalIconButtonTest {
    @Test
    void largerClickTargetsCenterTheSameFrameWithoutStretchingIt() {
        assertEquals(new TerminalLayout.Rect(8, 12, 18, 18), TerminalActionIcon.bounds(7, 11, 20, 20));
        assertEquals(new TerminalLayout.Rect(11, 13, 18, 18), TerminalActionIcon.bounds(7, 11, 26, 22));
        assertEquals(
                48,
                TerminalActionIcon.glyph(TerminalActionIcon.Symbol.ADD).source().width());
    }

    @Test
    void undersizedBoundsDoNotRenderOutsideTheWidget() {
        assertNull(TerminalActionIcon.bounds(7, 11, 16, 16));
        assertNull(TerminalActionIcon.bounds(0, 0, 26, 17));
    }

    @Test
    void disabledPriorityAndBlueInteractionsComeFromTheRealProductionStyle() {
        var disabled = TerminalActionIcon.style(false, false, false, false);
        assertEquals(disabled, TerminalActionIcon.style(false, true, true, true));
        assertEquals(TerminalTheme.DISABLED_TEXT, disabled.text());
        assertEquals(
                TerminalTheme.HOVER_LINE,
                TerminalActionIcon.style(true, true, false, false).border());
        assertEquals(
                TerminalTheme.ACCENT,
                TerminalActionIcon.style(true, false, true, false).border());
    }
}
