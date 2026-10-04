// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.loongin.omniresonance.networking.TunnelSummary;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TerminalTunnelConfirmationTest {
    @Test
    void disablingFirstOpensConfirmationWhileEnablingKeepsItsExistingDirectAction() {
        var bounds = new TerminalLayout.Rect(100, 80, 80, 20);
        int[] confirms = {0}, enables = {0};
        var active = new TunnelSummary(new UUID(1, 2), "Tunnel", 1, true, 2, 3);
        var button = NetworkSetupScreen.buildTunnelToggleButton(
                bounds, active, () -> confirms[0]++, () -> enables[0]++, true);
        button.onPress();
        assertEquals(1, confirms[0]);
        assertEquals(0, enables[0]);
        var disabled = new TunnelSummary(active.tunnelId(), active.name(), 2, false, 2, 3);
        NetworkSetupScreen.buildTunnelToggleButton(bounds, disabled, () -> confirms[0]++, () -> enables[0]++, true)
                .onPress();
        assertEquals(1, confirms[0]);
        assertEquals(1, enables[0]);
        var pending = NetworkSetupScreen.buildTunnelToggleButton(
                bounds, active, () -> confirms[0]++, () -> enables[0]++, false);
        assertFalse(pending.active);
        pending.onPress();
        assertEquals(1, confirms[0]);
    }
}
