// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminalQuickMoveDragTest {
    @Test
    void sweepingNineSlotsProducesExactlyNineOrderedSingleStackIntents() {
        var drag = new TerminalQuickMoveDrag();
        var intents = new java.util.ArrayList<TerminalClickQueue.Click>();
        drag.begin(0, 0, true);
        intents.add(new TerminalClickQueue.Click(0, 0, 0, true));
        assertFalse(drag.enter(0, 0, true, true));
        for (int slot = 1; slot < 9; slot++) {
            assertTrue(drag.enter(slot, 0, true, true));
            intents.add(new TerminalClickQueue.Click(0, slot, 0, true));
            assertFalse(drag.enter(slot, 0, true, true));
        }
        for (int slot = 0; slot < 9; slot++) {
            var click = intents.get(slot);
            assertEquals(slot, click.slot());
            assertTrue(click.shift());
            assertFalse(click.bulk(), "Sweeping must not become Shift-double-click all-matching transfer");
        }
        assertEquals(9, intents.size());
    }

    @Test
    void leavingAndReenteringACellIsANewExplicitMoveButStationaryEventsAreNot() {
        var drag = new TerminalQuickMoveDrag();
        drag.begin(-1, 0, true);
        assertFalse(drag.enter(-1, 0, true, true));
        assertTrue(drag.enter(-2, 0, true, true));
        assertFalse(drag.enter(TerminalQuickMoveDrag.OUTSIDE, 0, true, true));
        assertTrue(drag.enter(-2, 0, true, true));
        assertFalse(drag.enter(-2, 0, true, true));
    }

    @Test
    void releaseOrCarriedItemsStopTheGestureButShiftOnlyPausesTransfers() {
        var drag = new TerminalQuickMoveDrag();
        assertFalse(drag.enter(0, 0, true, true));
        for (int button : new int[] {1, 2}) {
            drag.begin(0, button, true);
            assertFalse(drag.enter(1, 0, true, true));
        }
        drag.begin(0, 0, false);
        assertFalse(drag.enter(1, 0, true, true));
        drag.begin(0, 0, true);
        assertFalse(drag.enter(1, 0, false, true));
        assertFalse(drag.enter(1, 0, true, true));
        assertTrue(drag.enter(2, 0, true, true));
        drag.begin(0, 0, true);
        assertFalse(drag.enter(1, 0, true, false));
        drag.begin(0, 0, true);
        drag.clear();
        assertFalse(drag.enter(1, 0, true, true));
    }
}
