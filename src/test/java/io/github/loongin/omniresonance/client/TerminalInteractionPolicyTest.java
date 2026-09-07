// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.ChannelPage;
import io.github.loongin.omniresonance.networking.NetworkSummary;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import io.github.loongin.omniresonance.networking.TunnelPage;
import io.github.loongin.omniresonance.networking.TunnelSummary;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TerminalInteractionPolicyTest {
    @Test
    void topBarChoosesCreationSettingsOrNoActionForTheCurrentPage() {
        NetworkSummary network = new NetworkSummary(new UUID(1, 1), new UUID(2, 2), "Network");
        NetworkTerminalState.TunnelList tunnels =
                new NetworkTerminalState.TunnelList(network, new TunnelPage(List.of(), 0, false, false));
        assertEquals(TerminalHeaderLayout.Action.CREATE, TerminalInteractionPolicy.topBarAction(true, null));
        assertEquals(
                TerminalHeaderLayout.Action.CREATE,
                TerminalInteractionPolicy.topBarAction(true, new NetworkTerminalState.NetworkRoot(network)));
        assertEquals(TerminalHeaderLayout.Action.CREATE, TerminalInteractionPolicy.topBarAction(true, tunnels));
        assertEquals(
                TerminalInteractionPolicy.CreateTarget.TUNNEL, TerminalInteractionPolicy.createTarget(true, tunnels));
        assertEquals(TerminalHeaderLayout.Action.NONE, TerminalInteractionPolicy.topBarAction(false, null));
        TunnelSummary tunnel = new TunnelSummary(new UUID(3, 3), "Tunnel", 0, true, 1, 0);
        assertEquals(
                TerminalHeaderLayout.Action.SETTINGS,
                TerminalInteractionPolicy.topBarAction(
                        true,
                        new NetworkTerminalState.ChannelList(
                                network, tunnel, new ChannelPage(List.of(), 0, false, false))));
        assertEquals(
                TerminalHeaderLayout.Action.NONE,
                TerminalInteractionPolicy.topBarAction(true, new NetworkTerminalState.TunnelSettings(network, tunnel)));
    }

    @Test
    void createIconTargetsOnlyTheObjectOwnedByTheCurrentView() {
        NetworkSummary network = new NetworkSummary(new UUID(1, 1), new UUID(2, 2), "Network");

        assertEquals(
                TerminalInteractionPolicy.CreateTarget.NETWORK, TerminalInteractionPolicy.createTarget(true, null));
        assertEquals(
                TerminalInteractionPolicy.CreateTarget.TUNNEL,
                TerminalInteractionPolicy.createTarget(
                        true,
                        new NetworkTerminalState.TunnelList(network, new TunnelPage(List.of(), 0, false, false))));
        assertEquals(
                TerminalInteractionPolicy.CreateTarget.NETWORK,
                TerminalInteractionPolicy.createTarget(true, new NetworkTerminalState.NetworkRoot(network)));
        assertEquals(
                TerminalInteractionPolicy.CreateTarget.NONE,
                TerminalInteractionPolicy.createTarget(
                        true,
                        new NetworkTerminalState.ChannelList(
                                network,
                                new TunnelSummary(new UUID(3, 3), "Tunnel", 0, true, 1, 0),
                                new ChannelPage(List.of(), 0, false, false))));
        assertEquals(TerminalInteractionPolicy.CreateTarget.NONE, TerminalInteractionPolicy.createTarget(false, null));
    }

    @Test
    void createOverlaySuppressesTheEmptyDirectoryUnderlay() {
        assertFalse(TerminalInteractionPolicy.renderEmptyDirectory(true));
        assertTrue(TerminalInteractionPolicy.renderEmptyDirectory(false));
    }

    @Test
    void selectedNetworkEntersHomeUnlessCreationOverlayOwnsTheScreen() {
        assertTrue(TerminalInteractionPolicy.enterNetworkHome(true, false, false));
        assertFalse(TerminalInteractionPolicy.enterNetworkHome(false, false, false));
        assertFalse(TerminalInteractionPolicy.enterNetworkHome(true, true, false));
        assertFalse(TerminalInteractionPolicy.enterNetworkHome(true, false, true));
    }

    @Test
    void failedCreateRestoresDirtyDraft() {
        TerminalInteractionPolicy.DraftState failed = TerminalInteractionPolicy.DraftState.clear()
                .edited()
                .submitted()
                .completed(false);

        assertTrue(failed.dirty());
        assertFalse(failed.createPending());
    }

    @Test
    void successfulCreateClearsDraft() {
        TerminalInteractionPolicy.DraftState succeeded = TerminalInteractionPolicy.DraftState.clear()
                .edited()
                .submitted()
                .completed(true);

        assertFalse(succeeded.dirty());
        assertFalse(succeeded.createPending());
    }

    @Test
    void failedDraftRequiresConfirmationBeforeLeavingCreateOverlay() {
        TerminalInteractionPolicy.DraftState failed = TerminalInteractionPolicy.DraftState.clear()
                .edited()
                .submitted()
                .completed(false);

        assertEquals(
                TerminalInteractionPolicy.BackAction.CONFIRM_DRAFT,
                TerminalInteractionPolicy.backAction(false, false, true, failed, false));
    }

    @Test
    void pendingCreateBackHidesOnlyCreateOverlay() {
        TerminalInteractionPolicy.DraftState pending =
                TerminalInteractionPolicy.DraftState.clear().edited().submitted();

        assertEquals(
                TerminalInteractionPolicy.BackAction.HIDE_CREATE_OVERLAY,
                TerminalInteractionPolicy.backAction(false, false, true, pending, false));
    }

    @Test
    void dropdownClosesBeforeUnderlyingNavigation() {
        assertEquals(
                TerminalInteractionPolicy.BackAction.CLOSE_DROPDOWN,
                TerminalInteractionPolicy.backAction(
                        true,
                        false,
                        true,
                        TerminalInteractionPolicy.DraftState.clear().edited(),
                        true));
    }

    @Test
    void hiddenCreateFailureResurfacesBeforeCloseConfirmation() {
        TerminalInteractionPolicy.DraftState pending =
                TerminalInteractionPolicy.DraftState.clear().edited().submitted();
        assertEquals(
                TerminalInteractionPolicy.BackAction.HIDE_CREATE_OVERLAY,
                TerminalInteractionPolicy.backAction(false, false, true, pending, false));

        TerminalInteractionPolicy.CreateResult failed = TerminalInteractionPolicy.createResult(pending, false);

        assertTrue(failed.showCreateOverlay());
        assertEquals(
                TerminalInteractionPolicy.BackAction.CONFIRM_DRAFT,
                TerminalInteractionPolicy.backAction(
                        false, false, failed.showCreateOverlay(), failed.draftState(), false));
    }

    @Test
    void retryReplacementPreservesDraftAndOnboardingChoice() {
        TerminalInteractionPolicy.DraftState failed = TerminalInteractionPolicy.DraftState.clear()
                .edited()
                .submitted()
                .completed(false);

        TerminalInteractionPolicy.RetrySnapshot snapshot =
                TerminalInteractionPolicy.RetrySnapshot.capture("Factory Network", failed, true);

        assertEquals("Factory Network", snapshot.draft());
        assertTrue(snapshot.hasDraft());
        assertTrue(snapshot.draftState().dirty());
        assertFalse(snapshot.draftState().createPending());
        assertTrue(snapshot.onboardingSkipped());
    }

    @Test
    void topologyBackBlocksPendingAndConfirmsOnlyDirtyEdits() {
        assertEquals(
                TerminalInteractionPolicy.TopologyBackAction.BLOCK,
                TerminalInteractionPolicy.topologyBackAction(true, false, true));
        assertEquals(
                TerminalInteractionPolicy.TopologyBackAction.CLOSE_CONFIRMATION,
                TerminalInteractionPolicy.topologyBackAction(false, true, true));
        assertEquals(
                TerminalInteractionPolicy.TopologyBackAction.CONFIRM_DRAFT,
                TerminalInteractionPolicy.topologyBackAction(false, false, true));
        assertEquals(
                TerminalInteractionPolicy.TopologyBackAction.SEND_BACK,
                TerminalInteractionPolicy.topologyBackAction(false, false, false));
    }
}
