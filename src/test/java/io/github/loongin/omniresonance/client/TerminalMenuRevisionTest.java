// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TerminalMenuRevisionTest {
    @Test
    void cursorOnlyUpdatesUseTheAcknowledgedServerRevisionUntilAnotherNativeUpdate() {
        var revision = new TerminalMenuRevision();
        assertEquals(7, revision.current(7));
        revision.acknowledge(8, 7);
        assertEquals(8, revision.current(7));
        assertEquals(9, revision.current(9));
        revision.acknowledge(10, 9);
        assertEquals(10, revision.current(9));
        revision.acknowledge(0, 32767);
        assertEquals(0, revision.current(32767));
        assertEquals(1, revision.current(1));
        revision.clear();
        assertEquals(5, revision.current(5));
    }
}
