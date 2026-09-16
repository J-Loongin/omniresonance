// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NetworkSetupNodeClickTest {
    @Test
    void closingTheViewDuringHighlightOrTeleportDoesNotDereferenceClearedState() {
        TerminalNodesView[] active = {
            new TerminalNodesView(new UUID(1, 1), new UUID(2, 2), 1, ignored -> {}, (node, travel) -> {}, () -> {})
        };
        assertTrue(NetworkSetupScreen.dispatchNodeClick(() -> active[0], () -> {
            active[0] = null;
            return true;
        }));
    }
}
