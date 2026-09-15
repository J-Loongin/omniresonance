// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TerminalSyncConfigTest {
    @Test
    void defaultsAndCoupledByteBudgetsMatchTheConfirmedRegistry() {
        var settings = ServerSettings.defaults().terminalSync();
        assertEquals(262144, settings.bytesPerPlayer());
        assertEquals(1048576, settings.bytesServer());
        assertEquals(4, settings.concurrentFull());
        assertEquals(8192, settings.pendingEntries());
        assertThrows(IllegalArgumentException.class, () -> new ServerSettings.TerminalSync(262144, 16384, 4, 8192));
        assertThrows(IllegalArgumentException.class, () -> new ServerSettings.TerminalSync(1, 1048576, 4, 8192));
    }
}
