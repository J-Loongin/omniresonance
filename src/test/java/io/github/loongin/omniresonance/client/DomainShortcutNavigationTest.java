// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.NetworkSummary;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainShortcutNavigationTest {
    private static final NetworkSummary NETWORK = new NetworkSummary(new UUID(1, 1), new UUID(2, 2), "Network");

    @Test
    void domainEntryNeverAutomaticallyFocusesSearchButOrdinaryEditorsStillDo() {
        var selected = new java.util.concurrent.atomic.AtomicReference<
                net.minecraft.client.gui.components.events.GuiEventListener>();
        var font = new net.minecraft.client.gui.Font(
                id -> {
                    throw new AssertionError("No rendering");
                },
                false);
        var field = new TerminalSearchBox(font, 0, 0, 100, 20, net.minecraft.network.chat.Component.empty());
        NetworkSetupScreen.focusInitialEditor(false, field, selected::set);
        assertEquals(field, selected.get());
        NetworkSetupScreen.focusInitialEditor(true, field, selected::set);
        assertEquals(null, selected.get());
        NetworkSetupScreen.focusInitialEditor(true, field, selected::set);
        assertEquals(null, selected.get());
    }

    @Test
    void defaultMappingIsUnboundAndContextIncludesOnlyGameOrDomain() {
        assertEquals(
                com.mojang.blaze3d.platform.InputConstants.UNKNOWN, NetworkTerminalClient.DOMAIN_KEY.getDefaultKey());
        assertTrue(DomainShortcutNavigation.contextActive(true, false, false));
        assertTrue(DomainShortcutNavigation.contextActive(true, true, true));
        assertFalse(DomainShortcutNavigation.contextActive(true, true, false));
        assertFalse(DomainShortcutNavigation.contextActive(false, false, false));
    }

    @Test
    void openingWaitsForAuthorizedNetworkAndIsConsumedExactlyOnce() {
        var intent = new DomainShortcutNavigation(true);
        assertEquals(DomainShortcutNavigation.Action.NONE, intent.resolve(null, false));
        var root = new NetworkTerminalState.NetworkRoot(NETWORK, false);
        assertEquals(DomainShortcutNavigation.Action.NONE, intent.resolve(root, true));
        assertTrue(intent.pending());
        assertEquals(DomainShortcutNavigation.Action.OPEN, intent.resolve(root, false));
        assertFalse(intent.pending());
        assertEquals(DomainShortcutNavigation.Action.NONE, intent.resolve(root, false));
    }

    @Test
    void unavailableOrClosedRequestsDoNotReopenOnLaterUpdates() {
        var intent = new DomainShortcutNavigation(true);
        assertEquals(
                DomainShortcutNavigation.Action.UNAVAILABLE,
                intent.resolve(new NetworkTerminalState.NetworkRoot(NETWORK, true), false));
        assertEquals(
                DomainShortcutNavigation.Action.NONE,
                intent.resolve(new NetworkTerminalState.NetworkRoot(NETWORK, false), false));
        var closed = new DomainShortcutNavigation(true);
        closed.clear();
        assertEquals(
                DomainShortcutNavigation.Action.NONE,
                closed.resolve(new NetworkTerminalState.NetworkRoot(NETWORK, false), false));
        assertEquals(
                DomainShortcutNavigation.Action.NONE,
                new DomainShortcutNavigation(false)
                        .resolve(new NetworkTerminalState.NetworkRoot(NETWORK, false), false));
    }
}
