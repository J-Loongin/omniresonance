// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.security.EditLockTable;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TerminalEditSessionTest {
    @Test
    void aSecondEditCannotReplaceTheLeaseThatStillNeedsRelease() {
        var session = new TerminalEditSession();
        var token = new EditLockTable.Token(new UUID(1, 1), new UUID(2, 2), 1);
        var topology = new NetworkTopologyService.Edit(
                token, NetworkTopologyService.Kind.TUNNEL_COLLECTION, new UUID(3, 3), null, null, null, 0, 0);
        var first = new TerminalEditSession.Topology(topology);
        session.begin(first);
        assertSame(topology, session.topology());
        assertNull(session.removal());
        assertThrows(IllegalStateException.class, session::requireIdle);
        assertThrows(IllegalStateException.class, () -> session.begin(first));
        assertSame(first, session.current());
        session.clear();
        session.requireIdle();
        assertNull(session.topology());
        session.begin(first);
        assertSame(topology, session.topology());
    }
}
