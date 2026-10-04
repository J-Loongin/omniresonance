// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HighlightColourCycleTest {
    @Test
    void newHighlightsRotateWhileContinuationsHoldAndReconnectRestartsRose() {
        var cycle = new HighlightColourCycle();
        assertEquals(0xE8A1AF, cycle.begin(false));
        assertEquals(0xE8A1AF, cycle.begin(true));
        assertEquals(0xB1A3DA, cycle.begin(false));
        assertEquals(0xB1A3DA, cycle.begin(true));
        assertEquals(0x9CCBD9, cycle.begin(false));
        assertEquals(0xDEC99A, cycle.begin(false));
        assertEquals(0xE8A1AF, cycle.begin(false));
        cycle.reset();
        assertEquals(0xE8A1AF, cycle.begin(false));
    }
}
