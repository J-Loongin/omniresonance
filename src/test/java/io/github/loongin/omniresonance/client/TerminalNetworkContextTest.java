// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.FilterPresetPage;
import io.github.loongin.omniresonance.networking.NetworkSummary;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TerminalNetworkContextTest {
    private static final NetworkSummary NETWORK = new NetworkSummary(new UUID(1, 1), new UUID(2, 2), "Fresh network");

    @Test
    void inventorySubpageKeepsTheNetworkLabelButCannotSwitchNetworks() {
        var context = new TerminalNetworkContext();
        context.apply(new NetworkTerminalState.NetworkRoot(NETWORK));
        assertTrue(context.selectable());
        context.open = true;
        context.readOnly(true);
        assertFalse(context.selectable());
        assertFalse(context.open);
        assertEquals(NETWORK.name(), context.label().getString());
        assertNull(context.buildSelector(new TerminalLayout.Rect(0, 0, 100, 20), "Network", true, () -> {}));
        context.readOnly(false);
        assertTrue(context.selectable());
    }

    @Test
    void screenEscapeRouteClosesHomeDropdownBeforeReturningOrClosingScreen() {
        var context = new TerminalNetworkContext();
        context.apply(new NetworkTerminalState.NetworkRoot(NETWORK));
        context.open = true;
        int[] rebuilds = {0};
        assertTrue(NetworkSetupScreen.closeLocalTopologyLayer(
                context, new TerminalFilterView(() -> {}), () -> rebuilds[0]++));
        assertFalse(context.open);
        assertEquals(1, rebuilds[0]);
        assertFalse(NetworkSetupScreen.closeLocalTopologyLayer(
                context, new TerminalFilterView(() -> {}), () -> rebuilds[0]++));
    }

    @Test
    void detachedSelectorCannotControlAnotherNetworksHome() {
        var view = new TerminalNetworkContext();
        view.apply(new NetworkTerminalState.NetworkRoot(NETWORK));
        var old = view.buildSelector(new TerminalLayout.Rect(0, 0, 100, 20), "Old", true, () -> {});
        view.apply(
                new NetworkTerminalState.NetworkRoot(new NetworkSummary(new UUID(4, 4), NETWORK.ownerId(), "Other")));
        old.onPress();
        assertFalse(view.open);
    }

    @Test
    void actualSelectorBuilderExistsOnlyOnHomeAndUsesAuthoritativeContext() {
        var view = new TerminalNetworkContext();
        var bounds = new TerminalLayout.Rect(20, 20, 100, 20);
        view.apply(null);
        assertNull(view.buildSelector(bounds, "Name", true, () -> {}));
        view.apply(new NetworkTerminalState.NetworkRoot(NETWORK));
        var selector = view.buildSelector(bounds, "Name", true, () -> {});
        assertNotNull(selector);
        selector.onPress();
        assertTrue(view.open);
        view.apply(new NetworkTerminalState.Filters(NETWORK, new FilterPresetPage(java.util.List.of(), 0, 0, 0)));
        assertFalse(view.open);
        assertNull(view.buildSelector(bounds, "Name", true, () -> {}));
        assertEquals("Fresh network", view.network().name());
        assertEquals("Fresh network", view.label().getString());
        selector.onPress();
        assertFalse(view.open, "A detached home selector must not reopen a hidden dropdown");
        view.apply(new NetworkTerminalState.NetworkRoot(NETWORK));
        assertNotNull(view.buildSelector(bounds, "Name", true, () -> {}));
    }

    @Test
    void navigationDropsHiddenRowsBoundsAndFirstClickInterception() {
        var view = new TerminalNetworkContext();
        view.apply(new NetworkTerminalState.NetworkRoot(NETWORK));
        view.open = true;
        view.bounds = new TerminalLayout.Rect(20, 40, 100, 20);
        var row =
                new TerminalButton(20, 40, 100, 20, net.minecraft.network.chat.Component.empty(), ignored -> {}, false);
        view.buttons.add(row);
        view.apply(new NetworkTerminalState.Filters(NETWORK, new FilterPresetPage(java.util.List.of(), 0, 0, 0)));
        assertFalse(view.open);
        assertTrue(view.buttons.isEmpty());
        assertEquals(0, view.bounds.width());
        assertFalse(row.active);
        assertFalse(row.visible);
        assertFalse(view.intercepts());
    }

    @Test
    void compactHeaderContextStaysInsideRemainingHeaderBeforeFixedGear() {
        for (int[] size : new int[][] {{320, 240}, {427, 240}, {960, 540}}) {
            var layout = TerminalLayout.calculate(size[0], size[1]);
            var header = TerminalHeaderLayout.atRightEdge(TerminalHeaderLayout.topBarContent(layout.window()), true);
            var context = TerminalNetworkContext.layout(header.remaining(), layout.compact(), false);
            assertTrue(context.x() >= header.remaining().x());
            assertTrue(context.right() + TerminalLayout.GAP <= header.action().x());
            assertEquals(20, context.height());
        }
    }
}
