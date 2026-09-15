// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminalQuickMoveGestureTest {
    @Test
    void onlyTwoShiftLeftClicksOnTheSameSlotWithin250MillisSelectBulk() {
        var gesture = new TerminalQuickMoveGesture();
        assertFalse(gesture.click(9, 0, true, 100));
        assertTrue(gesture.click(9, 0, true, 350));
        assertFalse(gesture.click(9, 0, true, 351));
        assertFalse(gesture.click(9, 0, true, 602));
        assertFalse(gesture.click(10, 0, true, 603));
        assertFalse(gesture.click(10, 1, true, 604));
        assertFalse(gesture.click(10, 0, true, 605));
        gesture.clear();
        assertFalse(gesture.click(10, 0, true, 606));
    }
}
