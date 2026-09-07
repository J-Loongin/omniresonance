// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.networking.NodeChannelPage;
import io.github.loongin.omniresonance.networking.NodeChannelSummary;
import io.github.loongin.omniresonance.networking.NodeMenuNodeSummary;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import io.github.loongin.omniresonance.networking.NodeNetworkSummary;
import io.github.loongin.omniresonance.networking.NodeTunnelPage;
import io.github.loongin.omniresonance.networking.NodeTunnelSummary;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class NodeMenuInteractionPolicyTest {
    private static final UUID SESSION = new UUID(200, 1);

    @Test
    void enabledTunnelListExposesSearchInsteadOfChannelCreationOrSettings() {
        NodeTunnelPage page = new NodeTunnelPage(java.util.List.of(), 0, false, false);
        assertEquals(
                "SEARCH",
                NodeMenuInteractionPolicy.topBarAction(new NodeMenuState.DirectTunnelList(node(true), page, 0))
                        .name());
        assertEquals(
                TerminalHeaderLayout.Action.NONE,
                NodeMenuInteractionPolicy.topBarAction(new NodeMenuState.DirectTunnelList(node(false), page, 0)));
    }

    @Test
    void topBarSwitchesBetweenChannelCreationAndSettingsWithoutActionsOnOtherPages() {
        NodeTunnelSummary tunnel = new NodeTunnelSummary(new UUID(203, 1), "Tunnel", 0, true, 1, 1, 1);
        NodeChannelSummary channel =
                new NodeChannelSummary(new UUID(203, 2), "Channel", 0, 1, 0, TransferDirection.INPUT);
        NodeChannelPage page = new NodeChannelPage(java.util.List.of(channel), 1, false, false);
        assertEquals(
                TerminalHeaderLayout.Action.CREATE,
                NodeMenuInteractionPolicy.topBarAction(new NodeMenuState.DirectChannelList(node(true), tunnel, page)));
        assertEquals(
                TerminalHeaderLayout.Action.SETTINGS,
                NodeMenuInteractionPolicy.topBarAction(
                        new NodeMenuState.DirectChannelRoot(node(true), tunnel, channel)));
        assertEquals(
                TerminalHeaderLayout.Action.NONE,
                NodeMenuInteractionPolicy.topBarAction(
                        new NodeMenuState.DirectChannelSettings(node(true), tunnel, channel)));
        assertEquals(
                TerminalHeaderLayout.Action.NONE,
                NodeMenuInteractionPolicy.topBarAction(new NodeMenuState.DirectChannelList(node(false), tunnel, page)));
        assertEquals(
                TerminalHeaderLayout.Action.NONE,
                NodeMenuInteractionPolicy.topBarAction(new NodeMenuState.LinkedRename(node(true))));
        assertEquals(TerminalHeaderLayout.Action.NONE, NodeMenuInteractionPolicy.topBarAction(null));
    }

    @Test
    void rootBackClosesButCleanAndDirtyEditsUseDifferentPaths() {
        NodeMenuInteractionPolicy.Model root = loaded(new NodeMenuState.LinkedRoot(node(true)));
        assertEquals(NodeMenuInteractionPolicy.BackAction.CLOSE_SCREEN, root.backAction());

        NodeMenuInteractionPolicy.Model editing = loaded(new NodeMenuState.LinkedRename(node(true)));
        assertEquals(NodeMenuInteractionPolicy.BackAction.CANCEL_EDIT, editing.backAction());
        assertEquals(
                NodeMenuInteractionPolicy.BackAction.CONFIRM_DISCARD,
                editing.edited().backAction());
        assertEquals(
                NodeMenuInteractionPolicy.BackAction.CLOSE_CONFIRMATION,
                editing.edited().confirmDiscard().backAction());
    }

    @Test
    void hierarchyBackReturnsOneServerOwnedLevelAtATime() {
        NodeMenuNodeSummary node = node(true);
        NodeMenuInteractionPolicy.Model modeRoot = loaded(new NodeMenuState.ModeRoot(node));
        NodeMenuInteractionPolicy.Model direct = loaded(
                new NodeMenuState.DirectTunnelList(node, new NodeTunnelPage(java.util.List.of(), 0, false, false), 0));
        NodeMenuInteractionPolicy.Model domain = loaded(new NodeMenuState.DomainRoot(node, null));

        assertEquals(NodeMenuInteractionPolicy.BackAction.CLOSE_SCREEN, modeRoot.backAction());
        assertEquals(NodeMenuInteractionPolicy.BackAction.SERVER_BACK, direct.backAction());
        assertEquals(NodeMenuInteractionPolicy.BackAction.SERVER_BACK, domain.backAction());
    }

    @Test
    void directionAndNetworkMoveStatesOwnTheirExactEditKinds() {
        NodeMenuNodeSummary node = node(true);
        io.github.loongin.omniresonance.networking.NodeTunnelSummary tunnel =
                new io.github.loongin.omniresonance.networking.NodeTunnelSummary(
                        new UUID(203, 1), "Tunnel", 0, true, 1, 1, 1);
        io.github.loongin.omniresonance.networking.NodeChannelSummary channel =
                new io.github.loongin.omniresonance.networking.NodeChannelSummary(
                        new UUID(203, 2), "Channel", 0, 1, 0, TransferDirection.INPUT);
        NodeNetworkSummary target = new NodeNetworkSummary(new UUID(203, 3), "Target", NodeNetworkSummary.Role.OWNER);

        assertEquals(
                NodeMenuInteractionPolicy.EditKind.BINDING,
                loaded(new NodeMenuState.DirectBindingEdit(node, tunnel, channel))
                        .editKind());
        assertEquals(
                NodeMenuInteractionPolicy.EditKind.DOMAIN,
                loaded(new NodeMenuState.DomainEdit(node, TransferDirection.OUTPUT))
                        .editKind());
        assertEquals(
                NodeMenuInteractionPolicy.EditKind.NETWORK_MOVE,
                loaded(new NodeMenuState.NetworkMoveEdit(node, target)).editKind());
        assertEquals(
                NodeMenuInteractionPolicy.EditKind.CHANNEL,
                loaded(new NodeMenuState.DirectChannelEdit(node, tunnel, null, "Channel 2"))
                        .editKind());
        assertEquals(
                NodeMenuInteractionPolicy.EditKind.CHANNEL_DELETE,
                loaded(new NodeMenuState.DirectChannelDelete(
                                node,
                                tunnel,
                                new io.github.loongin.omniresonance.networking.TopologyDeletionSummary(
                                        io.github.loongin.omniresonance.networking.TopologyDeletionSummary.Kind.CHANNEL,
                                        channel.channelId(),
                                        channel.name(),
                                        0,
                                        1)))
                        .editKind());
    }

    @Test
    void pendingMutationBlocksNavigationAndDoesNotPredictAuthority() {
        NodeMenuState.LinkedRoot authority = new NodeMenuState.LinkedRoot(node(true));
        NodeMenuInteractionPolicy.Model pending =
                loaded(authority).submit(NodeMenuInteractionPolicy.PendingKind.TOGGLE, 1);

        assertEquals(NodeMenuInteractionPolicy.BackAction.BLOCK, pending.backAction());
        assertSame(authority, pending.authoritative());
        assertTrue(((NodeMenuState.LinkedRoot) pending.authoritative()).node().enabled());
    }

    @Test
    void correctableFailurePreservesDraftAndActiveServerEdit() {
        NodeMenuState.LinkedRename editing = new NodeMenuState.LinkedRename(node(true));
        NodeMenuInteractionPolicy.Model pending =
                loaded(editing).edited().submit(NodeMenuInteractionPolicy.PendingKind.SAVE, 1);
        NodeMenuInteractionPolicy.Transition transition = pending.apply(
                new NodeMenuResponse.Failure(7, SESSION, 1, NodeMenuResponse.Reason.NAME_CONFLICT, editing));

        assertTrue(transition.accepted());
        assertEquals(
                NodeMenuInteractionPolicy.EditKind.RENAME, transition.model().editKind());
        assertTrue(transition.model().dirty());
        assertFalse(transition.model().mutationPending());
    }

    @Test
    void fatalFailureLeavesEditAndDiscardsUnusableDraft() {
        NodeMenuInteractionPolicy.Model pending = loaded(new NodeMenuState.LinkedRename(node(true)))
                .edited()
                .submit(NodeMenuInteractionPolicy.PendingKind.SAVE, 1);
        NodeMenuInteractionPolicy.Transition transition = pending.apply(new NodeMenuResponse.Failure(
                7, SESSION, 1, NodeMenuResponse.Reason.LOCK_EXPIRED, new NodeMenuState.LinkedRoot(node(true))));

        assertTrue(transition.accepted());
        assertEquals(NodeMenuInteractionPolicy.EditKind.NONE, transition.model().editKind());
        assertFalse(transition.model().dirty());
    }

    @Test
    void confirmedToggleReplacesAuthorityOnlyAfterMatchingResponse() {
        NodeMenuInteractionPolicy.Model pending = loaded(new NodeMenuState.LinkedRoot(node(true)))
                .submit(NodeMenuInteractionPolicy.PendingKind.TOGGLE, 1);
        NodeMenuInteractionPolicy.Transition transition =
                pending.apply(new NodeMenuResponse.State(7, SESSION, 1, new NodeMenuState.LinkedRoot(node(false))));

        assertTrue(transition.accepted());
        assertFalse(((NodeMenuState.LinkedRoot) transition.model().authoritative())
                .node()
                .enabled());
    }

    @Test
    void heartbeatStartsAtFortyTicksAndNeverBlocksOrdinaryNavigation() {
        NodeMenuInteractionPolicy.Model editing = loaded(new NodeMenuState.BlankEdit(
                        new NodeNetworkSummary(new UUID(201, 1), "Network", NodeNetworkSummary.Role.OWNER), 1))
                .armHeartbeat(100);

        assertFalse(editing.heartbeatDue(139));
        assertTrue(editing.heartbeatDue(140));
        NodeMenuInteractionPolicy.Model sent = editing.heartbeatSent(1, 140);
        assertFalse(sent.mutationPending());
        assertFalse(sent.heartbeatDue(179));
        assertTrue(sent.heartbeatDue(180));
    }

    @Test
    void staleOrUnrelatedResponseCannotReplaceCurrentAuthority() {
        NodeMenuInteractionPolicy.Model pending = loaded(new NodeMenuState.LinkedRoot(node(true)))
                .submit(NodeMenuInteractionPolicy.PendingKind.TOGGLE, 1);
        NodeMenuInteractionPolicy.Transition stale =
                pending.apply(new NodeMenuResponse.State(7, SESSION, 2, new NodeMenuState.LinkedRoot(node(false))));

        assertFalse(stale.accepted());
        assertSame(pending, stale.model());
        assertTrue(((NodeMenuState.LinkedRoot) stale.model().authoritative())
                .node()
                .enabled());
    }

    @Test
    void loadingStateHasNoLinkedHeaderSummary() {
        NodeMenuNodeSummary summary = node(true);

        assertNull(NodeMenuInteractionPolicy.linkedNode(null));
        assertSame(summary, NodeMenuInteractionPolicy.linkedNode(new NodeMenuState.LinkedRoot(summary)));
        assertSame(summary, NodeMenuInteractionPolicy.linkedNode(new NodeMenuState.DomainRoot(summary, null)));
    }

    @Test
    void confirmationLayerSuppressesUnderlyingBodyWidgets() {
        assertTrue(NodeMenuInteractionPolicy.bodyControlsVisible(false));
        assertFalse(NodeMenuInteractionPolicy.bodyControlsVisible(true));
    }

    private static NodeMenuInteractionPolicy.Model loaded(NodeMenuState state) {
        return NodeMenuInteractionPolicy.Model.loading()
                .apply(new NodeMenuResponse.State(7, SESSION, 0, state))
                .model();
    }

    private static NodeMenuNodeSummary node(boolean enabled) {
        return new NodeMenuNodeSummary(
                new UUID(202, 1),
                "Network",
                new UUID(202, 2),
                "Node",
                1,
                ResourceLocation.withDefaultNamespace("overworld"),
                new BlockPos(1, 2, 3),
                NodeForm.BLOCK,
                Direction.NORTH,
                enabled,
                true,
                NodeMode.DIRECT);
    }
}
