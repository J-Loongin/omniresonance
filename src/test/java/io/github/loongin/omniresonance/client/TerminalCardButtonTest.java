// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminalCardButtonTest {
    @Test
    void cardsOnlyRepeatTextWhenSomeOfItIsHidden() {
        assertFalse(TerminalCardButton.needsTooltip("Title", "Title", "Description", "Description", true));
        assertTrue(TerminalCardButton.needsTooltip("Long title", "Long…", "Description", "Description", true));
        assertTrue(TerminalCardButton.needsTooltip("Title", "Title", "Long description", "Long…", true));
        assertTrue(TerminalCardButton.needsTooltip("Title", "Title", "Description", "", false));
        assertFalse(TerminalCardButton.needsTooltip("Title", "Title", "", "", false));
    }
}
