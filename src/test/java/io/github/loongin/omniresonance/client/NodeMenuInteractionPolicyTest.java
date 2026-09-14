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
    void nodeProductionNetworkHeaderRendersPlainContextAcrossDescendantsAndEditStates() {
        var node = node(true);
        var tunnel = new NodeTunnelSummary(new UUID(203, 1), "Tunnel", 0, true, 1, 1, 1);
        var channel = new NodeChannelSummary(new UUID(203, 2), "Channel", 0, 1, 0, TransferDirection.INPUT);
        var target = new NodeNetworkSummary(new UUID(203, 3), "Target", NodeNetworkSummary.Role.OWNER);
        for (NodeMenuState state : java.util.List.of(
                new NodeMenuState.DirectTunnelList(node, new NodeTunnelPage(java.util.List.of(), 0, false, false), 0),
                new NodeMenuState.DirectChannelList(
                        node, tunnel, new NodeChannelPage(java.util.List.of(channel), 1, false, false)),
                new NodeMenuState.DirectChannelRoot(node, tunnel, channel),
                new NodeMenuState.DirectChannelSettings(node, tunnel, channel),
                new NodeMenuState.DirectBindingEdit(node, tunnel, channel),
                new NodeMenuState.LinkedRename(node),
                new NodeMenuState.LinkedMode(node),
                new NodeMenuState.DomainRoot(node, null),
                new NodeMenuState.DomainEdit(node, TransferDirection.INPUT),
                new NodeMenuState.NetworkMoveEdit(node, target))) {
            var model = loaded(state);
            assertFalse(
                    ResonanceNodeScreen.networkHeaderButtonVisible(model),
                    state.getClass().getSimpleName());
            org.junit.jupiter.api.Assertions.assertNull(ResonanceNodeScreen.buildNetworkHeaderButton(
                    new TerminalLayout.Rect(0, 0, 100, 20), node.networkName(), () -> model, () -> true, () -> {
                        throw new AssertionError("Read-only header dispatched migration");
                    }));
            assertEquals(
                    node.networkName(),
                    NodeMenuInteractionPolicy.linkedNode(state).networkName());
        }
        var model = loaded(new NodeMenuState.ModeRoot(node));
        var pending = model.submit(NodeMenuInteractionPolicy.PendingKind.NAVIGATE, 1);
        var bounds = new TerminalLayout.Rect(0, 0, 100, 20);
        var blocked = ResonanceNodeScreen.buildNetworkHeaderButton(
                bounds, node.networkName(), () -> pending, () -> true, () -> {
                    throw new AssertionError("Pending migration dispatched again");
                });
        assertFalse(blocked.active);
        blocked.onPress();
        var modal = ResonanceNodeScreen.buildNetworkHeaderButton(
                bounds, node.networkName(), () -> model, () -> false, () -> {
                    throw new AssertionError("Modal header dispatched migration");
                });
        assertFalse(modal.active);
        modal.onPress();
    }

    @Test
    void nodeNetworkHeaderHasOnlyHomeMigrationAndRejectsDetachedCallbacks() {
        var summary = node(true);
        var current = new java.util.concurrent.atomic.AtomicReference<>(loaded(new NodeMenuState.LinkedRoot(summary)));
        int[] calls = {0};
        var bounds = new TerminalLayout.Rect(5, 5, 100, 20);
        var button = ResonanceNodeScreen.buildNetworkHeaderButton(
                bounds, summary.networkName(), current::get, () -> true, () -> calls[0]++);
        org.junit.jupiter.api.Assertions.assertNotNull(button);
        button.onPress();
        assertEquals(1, calls[0]);
        current.set(loaded(new NodeMenuState.DomainRoot(
                summary, io.github.loongin.omniresonance.network.TransferDirection.INPUT)));
        org.junit.jupiter.api.Assertions.assertNull(ResonanceNodeScreen.buildNetworkHeaderButton(
                bounds, summary.networkName(), current::get, () -> true, () -> calls[0]++));
        assertFalse(ResonanceNodeScreen.networkHeaderButtonVisible(current.get()));
        button.onPress();
        assertEquals(1, calls[0]);
        current.set(loaded(new NodeMenuState.ModeRoot(summary)));
        org.junit.jupiter.api.Assertions.assertNotNull(ResonanceNodeScreen.buildNetworkHeaderButton(
                bounds, summary.networkName(), current::get, () -> true, () -> calls[0]++));
        current.set(loaded(new NodeMenuState.LinkedRoot(node(false))));
        org.junit.jupiter.api.Assertions.assertNull(ResonanceNodeScreen.buildNetworkHeaderButton(
                bounds, summary.networkName(), current::get, () -> true, () -> calls[0]++));
    }

    @Test
    void presetHeaderAndEscapeUseScreenRoutesBeforePendingNavigation() {
        var original = binding(new UUID(202, 1), new UUID(202, 2), new UUID(203, 2));
        assertEquals(TerminalHeaderLayout.Action.SEARCH, ResonanceNodeScreen.topBarAction(original, true));
        assertEquals(TerminalHeaderLayout.Action.NONE, ResonanceNodeScreen.topBarAction(original, false));
        var pending = loaded(original).edited().submit(NodeMenuInteractionPolicy.PendingKind.NAVIGATE, 1);
        assertEquals(
                ResonanceNodeScreen.LocalBackAction.CLOSE_MODAL,
                ResonanceNodeScreen.localBackAction(pending, true, false, true, true, true));
        assertEquals(
                ResonanceNodeScreen.LocalBackAction.CLOSE_PRESET_SEARCH,
                ResonanceNodeScreen.localBackAction(pending, false, false, true, true, true));
        assertEquals(
                ResonanceNodeScreen.LocalBackAction.CLOSE_PRESET,
                ResonanceNodeScreen.localBackAction(pending, false, false, true, true, false));
        assertEquals(NodeMenuInteractionPolicy.BackAction.CONFIRM_DISCARD, pending.exitAction());
        assertTrue(pending.dirty());
        for (var kind : java.util.List.of(
                NodeMenuInteractionPolicy.PendingKind.SAVE, NodeMenuInteractionPolicy.PendingKind.CANCEL)) {
            assertEquals(
                    ResonanceNodeScreen.LocalBackAction.BLOCK,
                    ResonanceNodeScreen.localBackAction(
                            loaded(original).edited().submit(kind, 1), false, false, true, true, true));
        }
    }

    @Test
    void screenEscapeDismissesInFlightPresetReadAndLateReplyRetainsDirtyParent() {
        var original = binding(new UUID(202, 1), new UUID(202, 2), new UUID(203, 2));
        var pending = loaded(original).edited().submit(NodeMenuInteractionPolicy.PendingKind.NAVIGATE, 1);
        var picker = new NodePresetPicker();
        picker.open();
        picker.nextRequest();
        assertEquals(
                ResonanceNodeScreen.LocalBackAction.CLOSE_PRESET,
                ResonanceNodeScreen.localBackAction(pending, false, false, true, picker.pending()));
        picker.close();
        assertFalse(picker.complete(original.presets()));
        var applied = pending.apply(new NodeMenuResponse.State(7, SESSION, 1, original));
        assertTrue(applied.accepted());
        assertTrue(applied.model().dirty());
        assertNull(picker.page());
        assertEquals(
                ResonanceNodeScreen.LocalBackAction.CONTINUE,
                ResonanceNodeScreen.localBackAction(applied.model(), false, false, false, picker.pending()));
        assertEquals(
                NodeMenuInteractionPolicy.BackAction.CONFIRM_DISCARD,
                applied.model().backAction());
    }

    @Test
    void screenLocalBackStillPrioritizesModalAndBlocksOtherPendingOperations() {
        var original = binding(new UUID(202, 1), new UUID(202, 2), new UUID(203, 2));
        var dirty = loaded(original).edited();
        var reading =
                dirty.submit(NodeMenuInteractionPolicy.PendingKind.NAVIGATE, 1).confirmDiscard();
        assertEquals(
                ResonanceNodeScreen.LocalBackAction.CLOSE_MODAL,
                ResonanceNodeScreen.localBackAction(reading, true, false, true, true));
        var reply = reading.apply(new NodeMenuResponse.State(7, SESSION, 1, original))
                .model();
        assertTrue(reply.dirty());
        assertTrue(reply.discardConfirmation());
        for (var kind : java.util.List.of(
                NodeMenuInteractionPolicy.PendingKind.SAVE,
                NodeMenuInteractionPolicy.PendingKind.CANCEL,
                NodeMenuInteractionPolicy.PendingKind.NAVIGATE)) {
            var pending = dirty.submit(kind, 1);
            assertEquals(
                    ResonanceNodeScreen.LocalBackAction.BLOCK,
                    ResonanceNodeScreen.localBackAction(pending, false, false, true, false));
        }
    }

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
    void confirmationLayerRetainsUnderlyingBodyWidgets() {
        assertTrue(NodeMenuInteractionPolicy.bodyControlsVisible(false));
        assertTrue(NodeMenuInteractionPolicy.bodyControlsVisible(true));
    }

    @Test
    void presetPageRefreshKeepsSameBindingDirtyAndBackRequiresDiscardConfirmation() {
        NodeMenuState.DirectBindingEdit original = binding(new UUID(202, 1), new UUID(202, 2), new UUID(203, 2));
        var page = new io.github.loongin.omniresonance.networking.FilterPresetPage(
                java.util.List.of(new io.github.loongin.omniresonance.networking.FilterPresetSummary(
                        new UUID(204, 128), "Next preset", 0, 0, true)),
                128,
                129,
                1);
        NodeMenuState.DirectBindingEdit refreshed = new NodeMenuState.DirectBindingEdit(
                original.node(), original.tunnel(), original.channel(), original.policy(), page, null);
        NodeMenuInteractionPolicy.Model dirty = loaded(original)
                .edited()
                .submit(NodeMenuInteractionPolicy.PendingKind.NAVIGATE, 1)
                .apply(new NodeMenuResponse.State(7, SESSION, 1, refreshed))
                .model();
        assertSame(refreshed, dirty.authoritative());
        assertTrue(dirty.dirty());
        assertEquals(NodeMenuInteractionPolicy.BackAction.CONFIRM_DISCARD, dirty.backAction());
        NodeMenuInteractionPolicy.Model clean = loaded(original)
                .submit(NodeMenuInteractionPolicy.PendingKind.NAVIGATE, 1)
                .apply(new NodeMenuResponse.State(7, SESSION, 1, refreshed))
                .model();
        assertFalse(clean.dirty());
        assertEquals(NodeMenuInteractionPolicy.BackAction.CANCEL_EDIT, clean.backAction());
    }

    @Test
    void bindingDirtyNeverCarriesToAnotherObjectOrNewEditSaveAndExit() {
        NodeMenuState.DirectBindingEdit original = binding(new UUID(202, 1), new UUID(202, 2), new UUID(203, 2));
        java.util.List<NodeMenuState> replacements = java.util.List.of(
                binding(new UUID(202, 99), new UUID(202, 2), new UUID(203, 2)),
                binding(new UUID(202, 1), new UUID(202, 99), new UUID(203, 2)),
                binding(new UUID(202, 1), new UUID(202, 2), new UUID(203, 99)),
                new NodeMenuState.LinkedRename(original.node()),
                new NodeMenuState.DirectChannelRoot(original.node(), original.tunnel(), original.channel()));
        for (NodeMenuState replacement : replacements) {
            NodeMenuInteractionPolicy.Model result = loaded(original)
                    .edited()
                    .submit(NodeMenuInteractionPolicy.PendingKind.NAVIGATE, 1)
                    .apply(new NodeMenuResponse.State(7, SESSION, 1, replacement))
                    .model();
            assertFalse(result.dirty());
            assertFalse(result.backAction() == NodeMenuInteractionPolicy.BackAction.CONFIRM_DISCARD);
        }
        for (NodeMenuInteractionPolicy.PendingKind kind : java.util.List.of(
                NodeMenuInteractionPolicy.PendingKind.BEGIN_EDIT,
                NodeMenuInteractionPolicy.PendingKind.SAVE,
                NodeMenuInteractionPolicy.PendingKind.CANCEL)) {
            NodeMenuInteractionPolicy.Model result = loaded(original)
                    .edited()
                    .submit(kind, 1)
                    .apply(new NodeMenuResponse.State(7, SESSION, 1, original))
                    .model();
            assertFalse(result.dirty());
        }
    }

    @Test
    void periodicStatusRequestDoesNotBlockNavigationOrBecomeADraft() {
        var binding = binding(new UUID(202, 1), new UUID(202, 2), new UUID(203, 2));
        var root = new NodeMenuState.DirectChannelRoot(binding.node(), binding.tunnel(), binding.channel());
        var polling = loaded(root).submit(NodeMenuInteractionPolicy.PendingKind.STATUS, 1);
        assertFalse(polling.mutationPending());
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> polling.submit(NodeMenuInteractionPolicy.PendingKind.STATUS, 2));
        assertEquals(NodeMenuInteractionPolicy.BackAction.SERVER_BACK, polling.backAction());
        var navigating = polling.submit(NodeMenuInteractionPolicy.PendingKind.BEGIN_EDIT, 2);
        assertFalse(navigating
                .apply(new NodeMenuResponse.State(7, SESSION, 1, root))
                .accepted());
        var editing = navigating
                .apply(new NodeMenuResponse.State(7, SESSION, 2, binding))
                .model()
                .edited();
        assertFalse(
                editing.apply(new NodeMenuResponse.State(7, SESSION, 1, root)).accepted());
        assertTrue(editing.dirty());
    }

    @Test
    void statusOnlyReplyRetainsWidgetsButStructuralAndFailureRepliesRebuild() {
        var binding = binding(new UUID(202, 1), new UUID(202, 2), new UUID(203, 2));
        var root = new NodeMenuState.DirectChannelRoot(binding.node(), binding.tunnel(), binding.channel());
        var polling = loaded(root).submit(NodeMenuInteractionPolicy.PendingKind.STATUS, 1);
        var refreshed = new NodeMenuState.DirectChannelRoot(
                root.node(),
                root.tunnel(),
                root.channel(),
                root.policy(),
                io.github.loongin.omniresonance.networking.NodeTransferStatus.NO_WORK_FACES);
        var status = polling.apply(new NodeMenuResponse.State(7, SESSION, 1, refreshed));
        assertTrue(status.accepted());
        assertSame(refreshed, status.model().authoritative());
        assertFalse(status.rebuild());
        assertFalse(status.model()
                .apply(new NodeMenuResponse.State(7, SESSION, 1, root))
                .accepted());
        var failure = polling.apply(new NodeMenuResponse.Failure(
                7, SESSION, 1, NodeMenuResponse.Reason.LOCK_EXPIRED, new NodeMenuState.Unavailable()));
        assertTrue(failure.accepted());
        assertTrue(failure.rebuild());
        assertFalse(failure.model().mutationPending());
        assertTrue(polling.apply(new NodeMenuResponse.State(7, SESSION, 1, new NodeMenuState.LinkedRoot(node(false))))
                .rebuild());
    }

    @Test
    void inventoryShortcutClosesAtAnyDepthButRequiresOneDirtyConfirmation() {
        var binding = binding(new UUID(202, 1), new UUID(202, 2), new UUID(203, 2));
        var root = new NodeMenuState.DirectChannelRoot(binding.node(), binding.tunnel(), binding.channel());
        assertEquals(
                NodeMenuInteractionPolicy.BackAction.CLOSE_SCREEN, loaded(root).exitAction());
        assertEquals(
                NodeMenuInteractionPolicy.BackAction.CLOSE_SCREEN,
                loaded(binding).exitAction());
        assertEquals(
                NodeMenuInteractionPolicy.BackAction.CONFIRM_DISCARD,
                loaded(binding).edited().exitAction());
        assertEquals(
                NodeMenuInteractionPolicy.BackAction.CONFIRM_DISCARD,
                loaded(binding).edited().confirmDiscard().exitAction());
        assertEquals(
                NodeMenuInteractionPolicy.BackAction.CLOSE_SCREEN,
                loaded(root)
                        .submit(NodeMenuInteractionPolicy.PendingKind.STATUS, 1)
                        .exitAction());
    }

    @Test
    void dirtyPreviewNavigationKeepsExitConfirmationAndDraftWhenItsResponseArrives() {
        var binding = binding(new UUID(202, 1), new UUID(202, 2), new UUID(203, 2));
        var pending = loaded(binding).edited().submit(NodeMenuInteractionPolicy.PendingKind.NAVIGATE, 1);
        assertEquals(NodeMenuInteractionPolicy.BackAction.CONFIRM_DISCARD, pending.exitAction());
        var confirmed = pending.confirmDiscard();
        assertTrue(confirmed.mutationPending());
        var replied = confirmed
                .apply(new NodeMenuResponse.State(7, SESSION, 1, binding))
                .model();
        assertTrue(replied.discardConfirmation());
        assertTrue(replied.dirty());
        assertFalse(replied.mutationPending());
        assertTrue(replied.continueEditing().dirty());
        assertFalse(replied.continueEditing().discardConfirmation());
        assertEquals(
                NodeMenuInteractionPolicy.BackAction.CLOSE_SCREEN,
                loaded(binding)
                        .edited()
                        .submit(NodeMenuInteractionPolicy.PendingKind.SAVE, 1)
                        .exitAction());
    }

    private static NodeMenuState.DirectBindingEdit binding(UUID networkId, UUID nodeId, UUID channelId) {
        NodeMenuNodeSummary template = node(true);
        NodeMenuNodeSummary node = new NodeMenuNodeSummary(
                networkId,
                template.networkName(),
                nodeId,
                template.nodeName(),
                template.revision(),
                template.dimension(),
                template.position(),
                template.form(),
                template.facing(),
                template.enabled(),
                template.chunkLoadingRequested(),
                template.mode());
        return new NodeMenuState.DirectBindingEdit(
                node,
                new NodeTunnelSummary(new UUID(203, 1), "Tunnel", 0, true, 1, 1, 1),
                new NodeChannelSummary(channelId, "Channel", 0, 1, 0, TransferDirection.INPUT));
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
