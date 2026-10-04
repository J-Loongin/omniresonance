// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class TerminalControlStyleTest {
    @Test
    void selectedControlsUseSelectionRatherThanTransientHoverColors() {
        var style = TerminalTheme.controlStyle(true, false, false, true, false, false);
        assertEquals(TerminalTheme.ACCENT_SOFT, style.surface());
        assertEquals(TerminalTheme.ACCENT, style.border());
    }

    @Test
    void keyboardFocusChangesTheBorderWithoutPretendingTheMouseIsHovering() {
        var style = TerminalTheme.controlStyle(true, false, true, false, false, false);
        assertEquals(TerminalTheme.RAISED, style.surface());
        assertEquals(TerminalTheme.ACCENT, style.border());
    }

    @Test
    void idlePrimaryActionsHaveTheirOwnBlueEmphasisAndDisabledAlwaysWins() {
        var primary = TerminalTheme.controlStyle(true, false, false, false, true, false);
        assertEquals(TerminalTheme.ACCENT_SOFT, primary.surface());
        assertEquals(TerminalTheme.HOVER_LINE, primary.border());
        var disabled = TerminalTheme.controlStyle(false, true, true, true, true, true);
        assertEquals(TerminalTheme.RAISED_DISABLED, disabled.surface());
        assertEquals(TerminalTheme.LINE, disabled.border());
        assertEquals(TerminalTheme.DISABLED_TEXT, disabled.text());
    }
}
