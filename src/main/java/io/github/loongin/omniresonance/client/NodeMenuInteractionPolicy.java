// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NodeMenuNodeSummary;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Pure node-Screen draft, response, navigation and heartbeat policy independent of rendering state. */
final class NodeMenuInteractionPolicy {
    static TerminalHeaderLayout.Action topBarAction(@Nullable NodeMenuState state) {
        NodeMenuNodeSummary node = linkedNode(state);
        if (node == null || !node.enabled()) {
            return TerminalHeaderLayout.Action.NONE;
        }
        if (state instanceof NodeMenuState.DirectTunnelList) {
            return TerminalHeaderLayout.Action.SEARCH;
        }
        if (state instanceof NodeMenuState.DirectChannelList) {
            return TerminalHeaderLayout.Action.CREATE;
        }
        return state instanceof NodeMenuState.DirectChannelRoot
                ? TerminalHeaderLayout.Action.SETTINGS
                : TerminalHeaderLayout.Action.NONE;
    }

    private static final long HEARTBEAT_INTERVAL_TICKS = 40;
    private static final Set<NodeMenuResponse.Reason> CORRECTABLE_FAILURES = Set.of(
            NodeMenuResponse.Reason.INVALID_NAME,
            NodeMenuResponse.Reason.NAME_CONFLICT,
            NodeMenuResponse.Reason.QUOTA_REACHED,
            NodeMenuResponse.Reason.RESET_REQUIRED);

    private NodeMenuInteractionPolicy() {}

    static boolean bodyControlsVisible(boolean confirmationOpen) {
        return !confirmationOpen;
    }

    static @Nullable NodeMenuNodeSummary linkedNode(@Nullable NodeMenuState state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case NodeMenuState.LinkedRoot root -> root.node();
            case NodeMenuState.LinkedRename rename -> rename.node();
            case NodeMenuState.LinkedMode mode -> mode.node();
            case NodeMenuState.ModeRoot root -> root.node();
            case NodeMenuState.NetworkSelection selection -> selection.node();
            case NodeMenuState.NetworkMoveEdit edit -> edit.node();
            case NodeMenuState.DirectTunnelList list -> list.node();
            case NodeMenuState.RestrictedTunnel restricted -> restricted.node();
            case NodeMenuState.DirectChannelList list -> list.node();
            case NodeMenuState.DirectBindingEdit edit -> edit.node();
            case NodeMenuState.DirectChannelRoot root -> root.node();
            case NodeMenuState.DirectChannelSettings settings -> settings.node();
            case NodeMenuState.DirectTunnelSwitch tunnelSwitch -> tunnelSwitch.node();
            case NodeMenuState.DomainRoot root -> root.node();
            case NodeMenuState.DomainEdit edit -> edit.node();
            case NodeMenuState.DirectChannelEdit edit -> edit.node();
            case NodeMenuState.DirectChannelDelete delete -> delete.node();
            default -> null;
        };
    }

    enum EditKind {
        NONE,
        BLANK,
        RENAME,
        MODE,
        NETWORK_MOVE,
        BINDING,
        DOMAIN,
        CHANNEL,
        CHANNEL_DELETE
    }

    enum PendingKind {
        PAGE,
        NAVIGATE,
        BEGIN_EDIT,
        SAVE,
        TOGGLE,
        CANCEL
    }

    enum BackAction {
        CLOSE_SCREEN,
        SERVER_BACK,
        CANCEL_EDIT,
        CONFIRM_DISCARD,
        CLOSE_CONFIRMATION,
        BLOCK
    }

    record Pending(PendingKind kind, long sequence) {
        Pending {
            Objects.requireNonNull(kind, "kind");
            if (sequence < 1) {
                throw new IllegalArgumentException("Pending node request sequence must be positive");
            }
        }
    }

    record Transition(Model model, boolean accepted) {
        Transition {
            Objects.requireNonNull(model, "model");
        }
    }

    record Model(
            @Nullable NodeMenuState authoritative,
            EditKind editKind,
            boolean dirty,
            boolean discardConfirmation,
            @Nullable Pending pending,
            int containerId,
            @Nullable UUID sessionId,
            long lastIssuedSequence,
            long expectedHeartbeatSequence,
            long nextHeartbeatTick) {
        Model {
            Objects.requireNonNull(editKind, "editKind");
            if ((authoritative == null) != (sessionId == null)
                    || (authoritative == null && containerId != -1)
                    || (authoritative != null && containerId < 0)
                    || lastIssuedSequence < 0
                    || expectedHeartbeatSequence < 0
                    || nextHeartbeatTick < 0
                    || (editKind == EditKind.NONE && (dirty || discardConfirmation))) {
                throw new IllegalArgumentException("Invalid node interaction model");
            }
        }

        static Model loading() {
            return new Model(null, EditKind.NONE, false, false, null, -1, null, 0, 0, 0);
        }

        boolean mutationPending() {
            return pending != null;
        }

        Model edited() {
            if (editKind == EditKind.NONE || pending != null) {
                throw new IllegalStateException("Only an idle node edit can become dirty");
            }
            return copy(
                    authoritative,
                    editKind,
                    true,
                    false,
                    null,
                    lastIssuedSequence,
                    expectedHeartbeatSequence,
                    nextHeartbeatTick);
        }

        Model confirmDiscard() {
            if (editKind == EditKind.NONE || !dirty || pending != null) {
                throw new IllegalStateException("Only an idle dirty node edit can request discard confirmation");
            }
            return copy(
                    authoritative,
                    editKind,
                    true,
                    true,
                    null,
                    lastIssuedSequence,
                    expectedHeartbeatSequence,
                    nextHeartbeatTick);
        }

        Model continueEditing() {
            return copy(
                    authoritative,
                    editKind,
                    dirty,
                    false,
                    pending,
                    lastIssuedSequence,
                    expectedHeartbeatSequence,
                    nextHeartbeatTick);
        }

        Model discardDraft() {
            if (editKind == EditKind.NONE) {
                return this;
            }
            return copy(
                    authoritative,
                    editKind,
                    false,
                    false,
                    pending,
                    lastIssuedSequence,
                    expectedHeartbeatSequence,
                    nextHeartbeatTick);
        }

        Model submit(PendingKind kind, long sequence) {
            Objects.requireNonNull(kind, "kind");
            if (authoritative == null || pending != null || sequence != lastIssuedSequence + 1) {
                throw new IllegalStateException("Node mutation request is not eligible");
            }
            return copy(
                    authoritative,
                    editKind,
                    dirty,
                    discardConfirmation,
                    new Pending(kind, sequence),
                    sequence,
                    expectedHeartbeatSequence,
                    nextHeartbeatTick);
        }

        Model armHeartbeat(long currentTick) {
            if (currentTick < 0) {
                throw new IllegalArgumentException("Client tick must be nonnegative");
            }
            long deadline = editKind == EditKind.NONE ? 0 : Math.addExact(currentTick, HEARTBEAT_INTERVAL_TICKS);
            return copy(
                    authoritative,
                    editKind,
                    dirty,
                    discardConfirmation,
                    pending,
                    lastIssuedSequence,
                    expectedHeartbeatSequence,
                    deadline);
        }

        boolean heartbeatDue(long currentTick) {
            return currentTick >= 0
                    && editKind != EditKind.NONE
                    && pending == null
                    && nextHeartbeatTick > 0
                    && currentTick >= nextHeartbeatTick;
        }

        Model heartbeatSent(long sequence, long currentTick) {
            if (!heartbeatDue(currentTick) || sequence != lastIssuedSequence + 1) {
                throw new IllegalStateException("Node heartbeat is not eligible");
            }
            return copy(
                    authoritative,
                    editKind,
                    dirty,
                    discardConfirmation,
                    null,
                    sequence,
                    sequence,
                    Math.addExact(currentTick, HEARTBEAT_INTERVAL_TICKS));
        }

        BackAction backAction() {
            if (discardConfirmation) {
                return BackAction.CLOSE_CONFIRMATION;
            }
            if (pending != null) {
                return BackAction.BLOCK;
            }
            if (editKind == EditKind.NONE) {
                return switch (authoritative) {
                    case NodeMenuState.NetworkSelection ignored -> BackAction.SERVER_BACK;
                    case NodeMenuState.DirectTunnelList ignored -> BackAction.SERVER_BACK;
                    case NodeMenuState.RestrictedTunnel ignored -> BackAction.SERVER_BACK;
                    case NodeMenuState.DirectChannelList ignored -> BackAction.SERVER_BACK;
                    case NodeMenuState.DirectChannelRoot ignored -> BackAction.SERVER_BACK;
                    case NodeMenuState.DirectChannelSettings ignored -> BackAction.SERVER_BACK;
                    case NodeMenuState.DirectTunnelSwitch ignored -> BackAction.SERVER_BACK;
                    case NodeMenuState.DomainRoot ignored -> BackAction.SERVER_BACK;
                    default -> BackAction.CLOSE_SCREEN;
                };
            }
            return dirty ? BackAction.CONFIRM_DISCARD : BackAction.CANCEL_EDIT;
        }

        Transition apply(NodeMenuResponse response) {
            Objects.requireNonNull(response, "response");
            if (!expected(response)) {
                return new Transition(this, false);
            }
            NodeMenuState latest;
            boolean preserveDraft = false;
            if (response instanceof NodeMenuResponse.State success) {
                latest = success.state();
            } else {
                NodeMenuResponse.Failure failure = (NodeMenuResponse.Failure) response;
                latest = failure.state() == null ? new NodeMenuState.Unavailable() : failure.state();
                preserveDraft = CORRECTABLE_FAILURES.contains(failure.reason())
                        && editKind != EditKind.NONE
                        && NodeMenuInteractionPolicy.editKind(latest) == editKind;
            }
            EditKind nextEdit = NodeMenuInteractionPolicy.editKind(latest);
            long nextIssued = Math.max(lastIssuedSequence, response.sequence());
            Model applied = new Model(
                    latest,
                    nextEdit,
                    preserveDraft && dirty,
                    false,
                    null,
                    response.containerId(),
                    response.sessionId(),
                    nextIssued,
                    0,
                    nextEdit == EditKind.NONE ? 0 : nextHeartbeatTick);
            return new Transition(applied, true);
        }

        private boolean expected(NodeMenuResponse response) {
            if (authoritative == null) {
                return response.sequence() == 0;
            }
            if (response.containerId() != containerId || !response.sessionId().equals(sessionId)) {
                return false;
            }
            if (pending != null) {
                return response.sequence() == pending.sequence();
            }
            return expectedHeartbeatSequence > 0 && response.sequence() == expectedHeartbeatSequence;
        }

        private Model copy(
                @Nullable NodeMenuState nextAuthority,
                EditKind nextEdit,
                boolean nextDirty,
                boolean nextConfirmation,
                @Nullable Pending nextPending,
                long nextIssued,
                long nextExpectedHeartbeat,
                long nextHeartbeat) {
            return new Model(
                    nextAuthority,
                    nextEdit,
                    nextDirty,
                    nextConfirmation,
                    nextPending,
                    containerId,
                    sessionId,
                    nextIssued,
                    nextExpectedHeartbeat,
                    nextHeartbeat);
        }
    }

    private static EditKind editKind(NodeMenuState state) {
        return switch (state) {
            case NodeMenuState.BlankEdit ignored -> EditKind.BLANK;
            case NodeMenuState.LinkedRename ignored -> EditKind.RENAME;
            case NodeMenuState.LinkedMode ignored -> EditKind.MODE;
            case NodeMenuState.NetworkMoveEdit ignored -> EditKind.NETWORK_MOVE;
            case NodeMenuState.DirectBindingEdit ignored -> EditKind.BINDING;
            case NodeMenuState.DomainEdit ignored -> EditKind.DOMAIN;
            case NodeMenuState.DirectChannelEdit ignored -> EditKind.CHANNEL;
            case NodeMenuState.DirectChannelDelete ignored -> EditKind.CHANNEL_DELETE;
            default -> EditKind.NONE;
        };
    }
}
