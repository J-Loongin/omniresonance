// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.ManagedNamePrefix;
import io.github.loongin.omniresonance.network.NetworkTopologyService;
import io.github.loongin.omniresonance.networking.NodeMenuNodeSummary;
import io.github.loongin.omniresonance.networking.NodeMenuRequest;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import io.github.loongin.omniresonance.registry.ModMenus;
import io.github.loongin.omniresonance.security.EditLockTable;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Zero-slot physical-node Menu binding one player container to an immutable position/session/node identity.
 *
 * <p>The client constructor owns only bootstrap values and receives no authority. The server Menu delegates pure
 * validity and close handling to its lifecycle service, never loads a chunk, simulates transfer, exposes inventory
 * slots or synchronously saves data. Later typed operations remain bound to this exact instance.
 */
public final class ResonanceNodeMenu extends AbstractContainerMenu {
    private final UUID playerId;
    private final BlockPos nodePosition;
    private final UUID sessionId;
    private final UUID nodeId;
    private @Nullable UUID linkedNetworkId;
    private final @Nullable NodeMenuService service;
    private NodeMenuState state;
    private @Nullable EditLockTable.Token editToken;
    private @Nullable UUID editNetworkId;
    private long editRevision = -1;
    private @Nullable NetworkTopologyService.Edit topologyEdit;
    private @Nullable NetworkTopologyService.DeletionEdit channelDeletion;
    private @Nullable NetworkTopologyService.TunnelSwitchEdit tunnelSwitchEdit;
    private @Nullable NodeManagementService.NetworkMoveEdit networkMoveEdit;
    private long lastSequence;
    private EditKind editKind = EditKind.NONE;
    private boolean closed;
    private @Nullable PolicyTransfer transfer;

    private static final class PolicyTransfer {
        final UUID id;
        final long sequence;
        final long deadline;
        final NetworkTopologyService.Edit edit;
        final UUID channel;
        final UUID tunnel;
        final int length;
        final @Nullable NodeMenuRequest.BeginPolicyUpload upload;
        int offset;

        PolicyTransfer(
                UUID id,
                long sequence,
                long deadline,
                NetworkTopologyService.Edit edit,
                UUID channel,
                UUID tunnel,
                int length,
                @Nullable NodeMenuRequest.BeginPolicyUpload upload) {
            this.id = id;
            this.sequence = sequence;
            this.deadline = deadline;
            this.edit = edit;
            this.channel = channel;
            this.tunnel = tunnel;
            this.length = length;
            this.upload = upload;
        }
    }

    UUID playerId() {
        return playerId;
    }

    ResonanceNodeMenu(
            int containerId,
            Inventory inventory,
            NodeMenuService service,
            BlockPos nodePosition,
            UUID sessionId,
            NodeMenuService.Initial initial) {
        super(ModMenus.RESONANCE_NODE.get(), containerId);
        playerId = Objects.requireNonNull(inventory, "inventory").player.getUUID();
        this.service = Objects.requireNonNull(service, "service");
        this.nodePosition = Objects.requireNonNull(nodePosition, "nodePosition").immutable();
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        NodeMenuService.Initial snapshot = Objects.requireNonNull(initial, "initial");
        nodeId = snapshot.nodeId();
        linkedNetworkId = snapshot.linkedNetworkId();
        state = snapshot.state();
    }

    private ResonanceNodeMenu(int containerId, Inventory inventory, BlockPos nodePosition, UUID sessionId) {
        super(ModMenus.RESONANCE_NODE.get(), containerId);
        playerId = Objects.requireNonNull(inventory, "inventory").player.getUUID();
        service = null;
        this.nodePosition = Objects.requireNonNull(nodePosition, "nodePosition").immutable();
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        nodeId = Util.NIL_UUID;
        linkedNetworkId = null;
        state = new NodeMenuState.Unavailable();
    }

    /** Client factory consuming exactly the position/session bytes supplied by the server open packet. */
    public static ResonanceNodeMenu createClient(
            int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        return new ResonanceNodeMenu(
                containerId,
                inventory,
                Objects.requireNonNull(extraData, "extraData").readBlockPos(),
                extraData.readUUID());
    }

    public BlockPos nodePosition() {
        return nodePosition;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public UUID nodeId() {
        return nodeId;
    }

    public Optional<UUID> linkedNetworkId() {
        return Optional.ofNullable(linkedNetworkId);
    }

    /** Server-thread lifecycle query covering both the linked network and any uncommitted target network. */
    boolean referencesNetwork(UUID networkId) {
        return networkId.equals(linkedNetworkId)
                || networkId.equals(editNetworkId)
                || (networkMoveEdit != null
                        && (networkId.equals(networkMoveEdit.sourceNetworkId())
                                || networkId.equals(networkMoveEdit.targetNetworkId())));
    }

    public NodeMenuState state() {
        return state;
    }

    /** Returns the bounded sequence-zero authoritative snapshot sent after the vanilla Menu-open packet. */
    public NodeMenuResponse.State initialResponse() {
        return new NodeMenuResponse.State(containerId, sessionId, 0, state);
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Applies one typed request from the actual server sender and returns its bounded authoritative response.
     * Successful heartbeat returns null; no client-supplied identity, node, network, revision or token is trusted.
     */
    public @Nullable NodeMenuResponse handle(ServerPlayer player, NodeMenuRequest request) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(request, "request");
        if (closed
                || service == null
                || !player.getUUID().equals(playerId)
                || request.containerId() != containerId
                || !request.sessionId().equals(sessionId)) {
            return failure(request, NodeMenuResponse.Reason.INVALID_REQUEST, null);
        }
        if (lastSequence == Long.MAX_VALUE || request.sequence() != lastSequence + 1) {
            return failure(request, NodeMenuResponse.Reason.STALE_REQUEST, null);
        }
        lastSequence = request.sequence();
        try {
            refreshBlankLink(player);
            return switch (request) {
                case NodeMenuRequest.BeginPolicyUpload begin -> beginPolicyUpload(player, begin);
                case NodeMenuRequest.ResourceCatalog page -> resourceCatalog(player, page);
                case NodeMenuRequest.Page page -> page(player, page);
                case NodeMenuRequest.BeginBlank begin -> beginBlank(player, begin);
                case NodeMenuRequest.Link link -> link(player, link);
                case NodeMenuRequest.BeginRename ignored -> beginRename(player, request);
                case NodeMenuRequest.Rename rename -> rename(player, rename);
                case NodeMenuRequest.BeginMode ignored -> beginMode(player, request);
                case NodeMenuRequest.SetMode mode -> setMode(player, mode);
                case NodeMenuRequest.SetEnabled enabled -> setEnabled(player, enabled);
                case NodeMenuRequest.SetChunkLoadingRequested requested -> setChunkLoadingRequested(player, requested);
                case NodeMenuRequest.Heartbeat ignored -> heartbeat(player, request);
                case NodeMenuRequest.CancelEdit ignored -> cancelEdit(player, request);
                case NodeMenuRequest.Back ignored -> back(player, request);
                case NodeMenuRequest.OpenModeRoot ignored -> openModeRoot(player, request);
                case NodeMenuRequest.OpenNetworkSelection ignored -> openNetworkSelection(player, request);
                case NodeMenuRequest.BeginNetworkMove move -> beginNetworkMove(player, move);
                case NodeMenuRequest.MoveNetwork move -> moveNetwork(player, move);
                case NodeMenuRequest.OpenDirect ignored -> openDirect(player, request);
                case NodeMenuRequest.PageTunnels page -> pageTunnels(player, page);
                case NodeMenuRequest.OpenTunnel open -> openTunnel(player, open);
                case NodeMenuRequest.PageChannels page -> pageChannels(player, page);
                case NodeMenuRequest.BeginBinding begin -> beginBinding(player, begin);
                case NodeMenuRequest.SetBindingDirection direction -> setBindingDirection(player, direction);
                case NodeMenuRequest.SaveResourcePolicy save -> saveResourcePolicy(player, save);
                case NodeMenuRequest.PageItemPresets page -> pageItemPresets(player, page);
                case NodeMenuRequest.PollItemStatus poll -> pollItemStatus(player, poll);
                case NodeMenuRequest.RemoveBinding ignored -> removeBinding(player, request);
                case NodeMenuRequest.OpenDomain ignored -> openDomain(player, request);
                case NodeMenuRequest.BeginDomainEdit ignored -> beginDomainEdit(player, request);
                case NodeMenuRequest.SetDomainDirection direction -> setDomainDirection(player, direction);
                case NodeMenuRequest.RemoveDomain ignored -> removeDomain(player, request);
                case NodeMenuRequest.BeginCreateChannel begin -> beginCreateChannel(player, begin);
                case NodeMenuRequest.BeginRenameChannel begin -> beginRenameChannel(player, begin);
                case NodeMenuRequest.SaveChannel save -> saveChannel(player, save);
                case NodeMenuRequest.RequestDeleteChannel delete -> requestDeleteChannel(player, delete);
                case NodeMenuRequest.ConfirmDeleteChannel ignored -> confirmDeleteChannel(player, request);
                case NodeMenuRequest.OpenChannel open -> openChannel(player, open);
                case NodeMenuRequest.OpenChannelSettings ignored -> openChannelSettings(player, request);
                case NodeMenuRequest.RequestTunnelSwitch switchRequest -> requestTunnelSwitch(player, switchRequest);
                case NodeMenuRequest.ConfirmTunnelSwitch ignored -> confirmTunnelSwitch(player, request);
            };
        } catch (NodeManagementService.Rejected rejected) {
            return rejected(player, request, rejected.reason());
        } catch (NetworkTopologyService.Rejected rejected) {
            return rejected(player, request, rejected.reason());
        } catch (io.github.loongin.omniresonance.filter.ItemFilterService.Rejected rejected) {
            if (rejected.reason() == io.github.loongin.omniresonance.filter.ItemFilterService.Reason.NO_ACCESS) {
                safeCancel(player);
                state = new NodeMenuState.NoAccess();
                linkedNetworkId = null;
                return failure(request, NodeMenuResponse.Reason.NO_ACCESS, null);
            }
            return failure(
                    request,
                    rejected.reason() == io.github.loongin.omniresonance.filter.ItemFilterService.Reason.INVALID_REQUEST
                            ? NodeMenuResponse.Reason.INVALID_REQUEST
                            : NodeMenuResponse.Reason.UNAVAILABLE,
                    state);
        } catch (IllegalArgumentException invalidRequest) {
            return failure(request, NodeMenuResponse.Reason.INVALID_REQUEST, state);
        } catch (RuntimeException failure) {
            safeCancel(player);
            state = new NodeMenuState.Unavailable();
            linkedNetworkId = null;
            return failure(request, NodeMenuResponse.Reason.INTERNAL_ERROR, null);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        Objects.requireNonNull(player, "player");
        if (closed || !player.getUUID().equals(playerId)) {
            return false;
        }
        if (service == null) {
            return true;
        }
        return player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && service.canKeepOpen(serverPlayer, this);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Objects.requireNonNull(player, "player");
        return ItemStack.EMPTY;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!closed && service != null) {
            if (player instanceof ServerPlayer serverPlayer) {
                safeCancel(serverPlayer);
            }
            service.closeMenu(this);
        } else {
            closed = true;
        }
    }

    void markClosed() {
        closed = true;
    }

    private NodeMenuResponse page(ServerPlayer player, NodeMenuRequest.Page request) {
        if (state instanceof NodeMenuState.BlankList) {
            state = new NodeMenuState.BlankList(service.page(player, request.anchor(), request.backwards()));
            return response(player, request);
        }
        if (state instanceof NodeMenuState.NetworkSelection && linkedNetworkId != null) {
            state = service.networkSelection(player, linkedNetworkId, nodeId, request.anchor(), request.backwards());
            return response(player, request);
        }
        return invalid(request);
    }

    private NodeMenuResponse beginBlank(ServerPlayer player, NodeMenuRequest.BeginBlank request) {
        if (!(state instanceof NodeMenuState.BlankList)) {
            return invalid(request);
        }
        EditLockTable.Token token = service.management().acquireBlank(player, nodePosition, request.networkId());
        try {
            long suggestion = service.management().suggestedNodeNumber(player, request.networkId());
            state = new NodeMenuState.BlankEdit(service.networkSummary(player, request.networkId()), suggestion);
            editToken = token;
            editNetworkId = request.networkId();
            editRevision = -1;
            editKind = EditKind.BLANK;
            return response(player, request);
        } catch (RuntimeException failure) {
            try {
                service.management().cancel(player, token);
            } catch (RuntimeException ignored) {
                // The original failure remains authoritative.
            }
            throw failure;
        }
    }

    private NodeMenuResponse link(ServerPlayer player, NodeMenuRequest.Link request) {
        if (editKind != EditKind.BLANK
                || !(state instanceof NodeMenuState.BlankEdit)
                || editToken == null
                || editNetworkId == null) {
            return invalid(request);
        }
        ManagedName name;
        try {
            name = new ManagedName(request.name());
        } catch (IllegalArgumentException invalidName) {
            return failure(request, NodeMenuResponse.Reason.INVALID_NAME, state);
        }
        UUID networkId = editNetworkId;
        NetworkNodeRecord linked = service.management().linkBlank(player, nodePosition, networkId, name, editToken);
        clearEdit();
        linkedNetworkId = networkId;
        state = service.modeRoot(player, networkId, linked.nodeId());
        return response(player, request);
    }

    private NodeMenuResponse beginRename(ServerPlayer player, NodeMenuRequest request) {
        NodeMenuNodeSummary node = nodeSummaryOrNull();
        if (node == null || linkedNetworkId == null || editKind != EditKind.NONE) {
            return invalid(request);
        }
        if (!node.enabled()) {
            return failure(request, NodeMenuResponse.Reason.NODE_DISABLED, state);
        }
        NodeManagementService.LinkedEdit edit = service.management().acquireLinked(player, linkedNetworkId, nodeId);
        editToken = edit.token();
        editNetworkId = linkedNetworkId;
        editRevision = edit.node().revision();
        editKind = EditKind.RENAME;
        state = service.linkedRename(player, linkedNetworkId, nodeId);
        return response(player, request);
    }

    private NodeMenuResponse rename(ServerPlayer player, NodeMenuRequest.Rename request) {
        if (editKind != EditKind.RENAME || !(state instanceof NodeMenuState.LinkedRename)) {
            return invalid(request);
        }
        ManagedName name;
        try {
            name = new ManagedName(request.name());
        } catch (IllegalArgumentException invalidName) {
            return failure(request, NodeMenuResponse.Reason.INVALID_NAME, state);
        }
        UUID networkId = requireEditNetwork();
        NetworkNodeRecord renamed =
                service.management().rename(player, networkId, editRevision, name, requireEditToken());
        clearEdit();
        state = service.linkedRoute(player, networkId, renamed.nodeId());
        return response(player, request);
    }

    private NodeMenuResponse beginMode(ServerPlayer player, NodeMenuRequest request) {
        NodeMenuNodeSummary node = nodeSummaryOrNull();
        if (node == null
                || linkedNetworkId == null
                || editKind != EditKind.NONE
                || (!(state instanceof NodeMenuState.ModeRoot) && !(state instanceof NodeMenuState.LinkedRoot))) {
            return invalid(request);
        }
        if (!node.enabled()) {
            return failure(request, NodeMenuResponse.Reason.NODE_DISABLED, state);
        }
        NodeManagementService.LinkedEdit edit = service.management().acquireLinked(player, linkedNetworkId, nodeId);
        editToken = edit.token();
        editNetworkId = linkedNetworkId;
        editRevision = edit.node().revision();
        editKind = EditKind.MODE;
        state = service.linkedMode(player, linkedNetworkId, nodeId);
        return response(player, request);
    }

    private NodeMenuResponse setMode(ServerPlayer player, NodeMenuRequest.SetMode request) {
        if (editKind != EditKind.MODE || !(state instanceof NodeMenuState.LinkedMode)) {
            return invalid(request);
        }
        UUID networkId = requireEditNetwork();
        NetworkNodeRecord changed = service.management()
                .setMode(player, networkId, editRevision, request.mode(), request.confirmedReset(), requireEditToken());
        clearEdit();
        state = service.linkedRoute(player, networkId, changed.nodeId());
        return response(player, request);
    }

    private NodeMenuResponse setEnabled(ServerPlayer player, NodeMenuRequest.SetEnabled request) {
        NodeMenuNodeSummary node = nodeSummaryOrNull();
        if (node == null || linkedNetworkId == null || editKind != EditKind.NONE) {
            return invalid(request);
        }
        UUID networkId = linkedNetworkId;
        NodeManagementService.LinkedEdit edit = service.management().acquireLinked(player, networkId, nodeId);
        try {
            NetworkNodeRecord changed = service.management()
                    .setEnabled(player, networkId, edit.node().revision(), request.enabled(), edit.token());
            state = service.linkedRoute(player, networkId, changed.nodeId());
            return response(player, request);
        } catch (RuntimeException failure) {
            safeCancel(player, edit.token());
            throw failure;
        }
    }

    private NodeMenuResponse setChunkLoadingRequested(
            ServerPlayer player, NodeMenuRequest.SetChunkLoadingRequested request) {
        NodeMenuNodeSummary node = nodeSummaryOrNull();
        if (node == null || linkedNetworkId == null || editKind != EditKind.NONE) {
            return invalid(request);
        }
        if (!node.enabled()) {
            return failure(request, NodeMenuResponse.Reason.NODE_DISABLED, state);
        }
        UUID networkId = linkedNetworkId;
        NodeManagementService.LinkedEdit edit = service.management().acquireLinked(player, networkId, nodeId);
        try {
            NetworkNodeRecord changed = service.management()
                    .setChunkLoadingRequested(
                            player, networkId, edit.node().revision(), request.requested(), edit.token());
            state = service.linkedRoute(player, networkId, changed.nodeId());
            return response(player, request);
        } catch (RuntimeException failure) {
            safeCancel(player, edit.token());
            throw failure;
        }
    }

    private NodeMenuResponse back(ServerPlayer player, NodeMenuRequest request) {
        if (state instanceof NodeMenuState.BlankEdit && editKind == EditKind.BLANK) {
            safeCancel(player);
            state = service.blankRoot(player);
            return response(player, request);
        }
        UUID networkId = linkedNetworkId;
        if (networkId == null) {
            return invalid(request);
        }
        if (state instanceof NodeMenuState.LinkedRename || state instanceof NodeMenuState.LinkedMode) {
            safeCancel(player);
            state = service.linkedRoute(player, networkId, nodeId);
        } else if (state instanceof NodeMenuState.NetworkMoveEdit) {
            safeCancel(player);
            state = service.networkSelection(player, networkId, nodeId, null, false);
        } else if (state instanceof NodeMenuState.DirectBindingEdit edit) {
            UUID tunnelId = edit.tunnel().tunnelId();
            UUID channelId = edit.channel().channelId();
            safeCancel(player);
            state = service.channelRoot(player, networkId, nodeId, tunnelId, channelId);
        } else if (state instanceof NodeMenuState.DirectChannelEdit edit) {
            UUID tunnelId = edit.tunnel().tunnelId();
            UUID channelId = edit.existing() == null ? null : edit.existing().channelId();
            safeCancel(player);
            state = channelId == null
                    ? service.tunnelState(player, networkId, nodeId, tunnelId, null, false)
                    : service.channelRoot(player, networkId, nodeId, tunnelId, channelId);
        } else if (state instanceof NodeMenuState.DirectChannelDelete delete) {
            UUID tunnelId = delete.tunnel().tunnelId();
            UUID channelId = delete.summary().objectId();
            safeCancel(player);
            state = service.channelRoot(player, networkId, nodeId, tunnelId, channelId);
        } else if (state instanceof NodeMenuState.DirectChannelSettings settings) {
            state = service.channelRoot(
                    player,
                    networkId,
                    nodeId,
                    settings.tunnel().tunnelId(),
                    settings.channel().channelId());
        } else if (state instanceof NodeMenuState.DirectChannelRoot root) {
            state = service.tunnelState(player, networkId, nodeId, root.tunnel().tunnelId(), null, false);
        } else if (state instanceof NodeMenuState.DirectTunnelSwitch) {
            safeCancel(player);
            state = service.directTunnels(player, networkId, nodeId, null, false);
        } else if (state instanceof NodeMenuState.DomainEdit) {
            safeCancel(player);
            state = service.domainRoot(player, networkId, nodeId);
        } else if (state instanceof NodeMenuState.RestrictedTunnel
                || state instanceof NodeMenuState.DirectChannelList) {
            state = service.directTunnels(player, networkId, nodeId, null, false);
        } else if (state instanceof NodeMenuState.DirectTunnelList
                || state instanceof NodeMenuState.DomainRoot
                || state instanceof NodeMenuState.NetworkSelection) {
            state = service.modeRoot(player, networkId, nodeId);
        } else if (state instanceof NodeMenuState.ModeRoot || state instanceof NodeMenuState.LinkedRoot) {
            player.closeContainer();
        } else {
            return invalid(request);
        }
        return response(player, request);
    }

    private NodeMenuResponse openModeRoot(ServerPlayer player, NodeMenuRequest request) {
        if (editKind != EditKind.NONE || linkedNetworkId == null || nodeSummaryOrNull() == null) {
            return invalid(request);
        }
        state = service.modeRoot(player, linkedNetworkId, nodeId);
        return response(player, request);
    }

    private NodeMenuResponse openNetworkSelection(ServerPlayer player, NodeMenuRequest request) {
        NodeMenuNodeSummary node = nodeSummaryOrNull();
        if (editKind != EditKind.NONE || linkedNetworkId == null || node == null) {
            return invalid(request);
        }
        if (!node.enabled()) {
            return failure(request, NodeMenuResponse.Reason.NODE_DISABLED, state);
        }
        state = service.networkSelection(player, linkedNetworkId, nodeId, null, false);
        return response(player, request);
    }

    private NodeMenuResponse beginNetworkMove(ServerPlayer player, NodeMenuRequest.BeginNetworkMove request) {
        if (!(state instanceof NodeMenuState.NetworkSelection)
                || editKind != EditKind.NONE
                || linkedNetworkId == null) {
            return invalid(request);
        }
        if (linkedNetworkId.equals(request.targetNetworkId())) {
            state = service.linkedRoute(player, linkedNetworkId, nodeId);
            return response(player, request);
        }
        NodeManagementService.NetworkMoveEdit edit =
                service.management().beginNetworkMove(player, linkedNetworkId, nodeId, request.targetNetworkId());
        networkMoveEdit = edit;
        editToken = edit.token();
        editNetworkId = linkedNetworkId;
        editRevision = edit.node().revision();
        editKind = EditKind.NETWORK_MOVE;
        state = service.networkMoveEdit(player, linkedNetworkId, nodeId, request.targetNetworkId());
        return response(player, request);
    }

    private NodeMenuResponse moveNetwork(ServerPlayer player, NodeMenuRequest.MoveNetwork request) {
        if (editKind != EditKind.NETWORK_MOVE
                || !(state instanceof NodeMenuState.NetworkMoveEdit)
                || networkMoveEdit == null) {
            return invalid(request);
        }
        ManagedName name;
        try {
            name = new ManagedName(request.name());
        } catch (IllegalArgumentException invalidName) {
            return failure(request, NodeMenuResponse.Reason.INVALID_NAME, state);
        }
        NodeManagementService.NetworkMoveEdit edit = networkMoveEdit;
        NetworkNodeRecord moved = service.management().moveNetwork(player, edit, name);
        clearEdit();
        linkedNetworkId = edit.targetNetworkId();
        state = service.modeRoot(player, edit.targetNetworkId(), moved.nodeId());
        return response(player, request);
    }

    private NodeMenuResponse openDirect(ServerPlayer player, NodeMenuRequest request) {
        if (editKind != EditKind.NONE || linkedNetworkId == null || nodeSummaryOrNull() == null) {
            return invalid(request);
        }
        state = service.directRoute(player, linkedNetworkId, nodeId);
        return response(player, request);
    }

    private NodeMenuResponse pageTunnels(ServerPlayer player, NodeMenuRequest.PageTunnels request) {
        if (!(state instanceof NodeMenuState.DirectTunnelList) || linkedNetworkId == null) {
            return invalid(request);
        }
        state = service.directTunnels(player, linkedNetworkId, nodeId, request.anchor(), request.backwards());
        return response(player, request);
    }

    private NodeMenuResponse openTunnel(ServerPlayer player, NodeMenuRequest.OpenTunnel request) {
        return openTunnelTarget(player, request, request.tunnelId());
    }

    private NodeMenuResponse requestTunnelSwitch(ServerPlayer player, NodeMenuRequest.RequestTunnelSwitch request) {
        return openTunnelTarget(player, request, request.targetTunnelId());
    }

    private NodeMenuResponse openTunnelTarget(ServerPlayer player, NodeMenuRequest request, UUID targetTunnelId) {
        if (!(state instanceof NodeMenuState.DirectTunnelList)
                || linkedNetworkId == null
                || editKind != EditKind.NONE) {
            return invalid(request);
        }
        NodeMenuState target = service.tunnelState(player, linkedNetworkId, nodeId, targetTunnelId, null, false);
        if (target instanceof NodeMenuState.RestrictedTunnel) {
            state = target;
            return response(player, request);
        }
        Optional<UUID> currentTunnel = service.topology().directTunnelId(player, linkedNetworkId, nodeId);
        if (currentTunnel.isEmpty() || currentTunnel.orElseThrow().equals(targetTunnelId)) {
            state = target;
            return response(player, request);
        }
        NetworkTopologyService.TunnelSwitchEdit switchEdit =
                service.topology().requestTunnelSwitch(player, linkedNetworkId, nodeId, targetTunnelId);
        try {
            state = service.tunnelSwitchState(player, linkedNetworkId, nodeId, switchEdit);
            tunnelSwitchEdit = switchEdit;
            topologyEdit = switchEdit.edit();
            editNetworkId = linkedNetworkId;
            editKind = EditKind.TUNNEL_SWITCH;
            return response(player, request);
        } catch (RuntimeException failure) {
            safeCancel(player, switchEdit.edit());
            throw failure;
        }
    }

    private NodeMenuResponse confirmTunnelSwitch(ServerPlayer player, NodeMenuRequest request) {
        if (!(state instanceof NodeMenuState.DirectTunnelSwitch)
                || linkedNetworkId == null
                || editKind != EditKind.TUNNEL_SWITCH
                || tunnelSwitchEdit == null) {
            return invalid(request);
        }
        NetworkTopologyService.TunnelSwitchEdit switchEdit = tunnelSwitchEdit;
        UUID targetTunnelId = switchEdit.targetTunnelId();
        service.topology().confirmTunnelSwitch(player, switchEdit);
        clearEdit();
        state = service.tunnelState(player, linkedNetworkId, nodeId, targetTunnelId, null, false);
        return response(player, request);
    }

    private NodeMenuResponse pageChannels(ServerPlayer player, NodeMenuRequest.PageChannels request) {
        if (!(state instanceof NodeMenuState.DirectChannelList list) || linkedNetworkId == null) {
            return invalid(request);
        }
        state = service.tunnelState(
                player, linkedNetworkId, nodeId, list.tunnel().tunnelId(), request.anchor(), request.backwards());
        return response(player, request);
    }

    private NodeMenuResponse openChannel(ServerPlayer player, NodeMenuRequest.OpenChannel request) {
        if (!(state instanceof NodeMenuState.DirectChannelList list)
                || linkedNetworkId == null
                || editKind != EditKind.NONE) {
            return invalid(request);
        }
        state = service.channelRoot(
                player, linkedNetworkId, nodeId, list.tunnel().tunnelId(), request.channelId());
        return response(player, request);
    }

    private NodeMenuResponse openChannelSettings(ServerPlayer player, NodeMenuRequest request) {
        if (!(state instanceof NodeMenuState.DirectChannelRoot root)
                || linkedNetworkId == null
                || editKind != EditKind.NONE) {
            return invalid(request);
        }
        state = service.channelSettings(
                player,
                linkedNetworkId,
                nodeId,
                root.tunnel().tunnelId(),
                root.channel().channelId());
        return response(player, request);
    }

    private NodeMenuResponse beginBinding(ServerPlayer player, NodeMenuRequest.BeginBinding request) {
        UUID tunnelId;
        UUID channelId;
        if (state instanceof NodeMenuState.DirectChannelRoot root) {
            tunnelId = root.tunnel().tunnelId();
            channelId = root.channel().channelId();
        } else if (state instanceof NodeMenuState.DirectChannelSettings settings) {
            tunnelId = settings.tunnel().tunnelId();
            channelId = settings.channel().channelId();
        } else {
            return invalid(request);
        }
        if (linkedNetworkId == null || editKind != EditKind.NONE || !channelId.equals(request.channelId())) {
            return invalid(request);
        }
        NetworkTopologyService.Edit edit = service.topology().acquireNode(player, linkedNetworkId, nodeId);
        try {
            state = service.bindingEdit(player, linkedNetworkId, nodeId, tunnelId, request.channelId());
            topologyEdit = edit;
            editNetworkId = linkedNetworkId;
            editKind = EditKind.BINDING;
            return response(player, request);
        } catch (RuntimeException failure) {
            safeCancel(player, edit);
            throw failure;
        }
    }

    private NodeMenuResponse setBindingDirection(ServerPlayer player, NodeMenuRequest.SetBindingDirection request) {
        if (editKind != EditKind.BINDING
                || !(state instanceof NodeMenuState.DirectBindingEdit editState)
                || topologyEdit == null
                || linkedNetworkId == null) {
            return invalid(request);
        }
        UUID tunnelId = editState.tunnel().tunnelId();
        UUID channelId = editState.channel().channelId();
        NetworkTopologyService.Edit edit = topologyEdit;
        service.topology()
                .setDirectBinding(
                        player, edit, editState.channel().channelId(), request.direction(), request.confirmedReset());
        clearEdit();
        state = service.channelRoot(player, linkedNetworkId, nodeId, tunnelId, channelId);
        return response(player, request);
    }

    private NodeMenuResponse pageItemPresets(ServerPlayer player, NodeMenuRequest.PageItemPresets request) {
        if (editKind != EditKind.BINDING
                || topologyEdit == null
                || linkedNetworkId == null
                || !(state instanceof NodeMenuState.DirectBindingEdit edit)) return invalid(request);
        state = service.bindingEdit(
                player,
                linkedNetworkId,
                nodeId,
                edit.tunnel().tunnelId(),
                edit.channel().channelId(),
                request.offset(),
                request.query(),
                request.libraryRevision());
        return response(player, request);
    }

    private NodeMenuResponse pollItemStatus(ServerPlayer player, NodeMenuRequest.PollItemStatus request) {
        if (linkedNetworkId == null
                || editKind != EditKind.NONE
                || !(state instanceof NodeMenuState.DirectChannelRoot root)) return invalid(request);
        state = service.channelRoot(
                player,
                linkedNetworkId,
                nodeId,
                root.tunnel().tunnelId(),
                root.channel().channelId());
        return response(player, request);
    }

    private NodeMenuResponse beginPolicyUpload(ServerPlayer player, NodeMenuRequest.BeginPolicyUpload request) {
        if (transfer != null
                || editKind != EditKind.BINDING
                || !(state instanceof NodeMenuState.DirectBindingEdit edit)
                || topologyEdit == null) return invalid(request);
        service.topology()
                .validateBindingEdit(player, topologyEdit, edit.channel().channelId());
        request.faces().validate(edit.node().form());
        long now = service.currentTick();
        service.transfers().beginUpload(playerId, sessionId, request.transfer(), request.length(), now);
        transfer = new PolicyTransfer(
                request.transfer(),
                request.sequence(),
                now + 200,
                topologyEdit,
                edit.channel().channelId(),
                edit.tunnel().tunnelId(),
                request.length(),
                request);
        return new NodeMenuResponse.UploadReady(containerId, sessionId, request.sequence(), request.transfer());
    }

    void cancelTransfer() {
        PolicyTransfer active = transfer;
        transfer = null;
        if (active != null && service != null) service.transfers().abort(playerId, sessionId, active.id);
    }

    private void authorizeTransfer(ServerPlayer player, PolicyTransfer active, long now) {
        if (closed
                || !player.getUUID().equals(playerId)
                || !canTransfer(player)
                || topologyEdit != active.edit
                || now >= active.deadline) throw new IllegalStateException("Expired policy context");
        service.topology().validateBindingEdit(player, active.edit, active.channel);
    }

    private boolean canTransfer(ServerPlayer player) {
        return service != null && service.canKeepOpen(player, this);
    }

    /** Actual-connection router; only approved uploads accept chunks, and arbitrary Begin grants no authority. */
    public @Nullable NodeMenuResponse handleTransfer(
            ServerPlayer player, io.github.loongin.omniresonance.networking.ManagementTransferMessage message) {
        PolicyTransfer active = transfer;
        if (active == null
                || !player.getUUID().equals(playerId)
                || !message.session().equals(sessionId)
                || !message.transfer().equals(active.id)) return null;
        boolean[] committing = {false};
        try {
            authorizeTransfer(player, active, service.currentTick());
            if (message instanceof io.github.loongin.omniresonance.networking.ManagementTransferMessage.Abort) {
                cancelTransfer();
                return null;
            }
            if (active.upload == null) throw new IllegalArgumentException("Unexpected upload direction");
            if (message instanceof io.github.loongin.omniresonance.networking.ManagementTransferMessage.Chunk chunk) {
                service.transfers()
                        .upload(playerId, sessionId, active.id, chunk.offset(), chunk.data(), service.currentTick());
                return null;
            }
            if (!(message instanceof io.github.loongin.omniresonance.networking.ManagementTransferMessage.Finish))
                throw new IllegalArgumentException("Unapproved Begin");
            io.github.loongin.omniresonance.transfer.ResourcePolicyEdit[] decoded =
                    new io.github.loongin.omniresonance.transfer.ResourcePolicyEdit[1];
            service.transfers()
                    .finishUpload(
                            playerId,
                            sessionId,
                            active.id,
                            service.currentTick(),
                            view -> {
                                decoded[0] =
                                        io.github.loongin.omniresonance.networking.ResourcePolicyEditCodec.decode(view);
                                service.topology()
                                        .validatePolicyIntent(player, active.edit, active.channel, decoded[0]);
                            },
                            () -> {
                                authorizeTransfer(player, active, service.currentTick());
                                return true;
                            },
                            view -> {
                                committing[0] = true;
                                service.topology()
                                        .saveDirectBinding(
                                                player,
                                                active.edit,
                                                active.channel,
                                                decoded[0],
                                                active.upload.faces(),
                                                active.upload.confirmedReset());
                            });
            transfer = null;
            clearEdit();
            state = service.channelRoot(player, linkedNetworkId, nodeId, active.tunnel, active.channel);
            return new NodeMenuResponse.State(containerId, sessionId, active.sequence, state);
        } catch (RuntimeException failure) {
            cancelTransfer();
            if (committing[0]) {
                safeCancel(player);
                state = new NodeMenuState.Unavailable();
                linkedNetworkId = null;
                return new NodeMenuResponse.Failure(
                        containerId, sessionId, active.sequence, NodeMenuResponse.Reason.INTERNAL_ERROR, null);
            }
            return new NodeMenuResponse.Failure(
                    containerId,
                    sessionId,
                    active.sequence,
                    NodeMenuResponse.Reason.INVALID_REQUEST,
                    state instanceof NodeMenuState.DirectBindingEdit edit ? edit.withPolicy(null) : state);
        }
    }

    /** Server tick sends one bounded fragment, with authorization and a fixed nonrenewable deadline. */
    void transferTick(ServerPlayer player, long now) {
        transferTick(
                player, now, payload -> net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload));
    }

    void transferTick(
            ServerPlayer player,
            long now,
            java.util.function.Consumer<net.minecraft.network.protocol.common.custom.CustomPacketPayload> sender) {
        PolicyTransfer active = transfer;
        if (active == null) return;
        try {
            authorizeTransfer(player, active, now);
            if (active.upload != null) return;
            byte[] bytes = service.transfers().nextDownload(playerId, sessionId, active.id, now);
            sender.accept(new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Chunk(
                    sessionId, active.id, active.offset, bytes));
            active.offset += bytes.length;
            if (active.offset == active.length) {
                transfer = null;
                sender.accept(new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Finish(
                        sessionId, active.id));
            }
        } catch (RuntimeException failure) {
            cancelTransfer();
            sender.accept(new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Abort(
                    sessionId, active.id));
        }
    }

    private NodeMenuResponse resourceCatalog(ServerPlayer player, NodeMenuRequest.ResourceCatalog request) {
        if (editKind != EditKind.BINDING
                || !(state instanceof NodeMenuState.DirectBindingEdit edit)
                || topologyEdit == null) return invalid(request);
        service.topology()
                .validateBindingEdit(player, topologyEdit, edit.channel().channelId());
        var page = io.github.loongin.omniresonance.networking.ResourceTypeCatalogPage.from(
                service.topology().resourceAdapters(),
                sessionId,
                request.offset(),
                262144,
                1 + net.minecraft.network.VarInt.getByteSize(containerId) + 16 + 8);
        return new NodeMenuResponse.Catalog(containerId, sessionId, request.sequence(), page);
    }

    private NodeMenuResponse saveResourcePolicy(ServerPlayer player, NodeMenuRequest.SaveResourcePolicy request) {
        if (editKind != EditKind.BINDING
                || !(state instanceof NodeMenuState.DirectBindingEdit editState)
                || topologyEdit == null
                || linkedNetworkId == null) {
            return invalid(request);
        }
        UUID tunnelId = editState.tunnel().tunnelId();
        UUID channelId = editState.channel().channelId();
        NetworkTopologyService.Edit edit = topologyEdit;
        service.topology()
                .saveDirectBinding(
                        player,
                        edit,
                        editState.channel().channelId(),
                        request.policy(),
                        request.workingFaces(),
                        request.confirmedReset());
        clearEdit();
        state = service.channelRoot(player, linkedNetworkId, nodeId, tunnelId, channelId);
        return response(player, request);
    }

    private NodeMenuResponse removeBinding(ServerPlayer player, NodeMenuRequest request) {
        NodeMenuState.DirectChannelRoot root = state instanceof NodeMenuState.DirectChannelRoot candidate
                ? candidate
                : state instanceof NodeMenuState.DirectChannelSettings settings
                        ? new NodeMenuState.DirectChannelRoot(settings.node(), settings.tunnel(), settings.channel())
                        : null;
        if (root != null
                && root.channel().currentDirection() != null
                && linkedNetworkId != null
                && editKind == EditKind.NONE) {
            NetworkTopologyService.Edit edit = service.topology().acquireNode(player, linkedNetworkId, nodeId);
            try {
                service.topology()
                        .removeDirectBinding(player, edit, root.channel().channelId());
                state = service.channelRoot(
                        player,
                        linkedNetworkId,
                        nodeId,
                        root.tunnel().tunnelId(),
                        root.channel().channelId());
                return response(player, request);
            } catch (RuntimeException failure) {
                safeCancel(player, edit);
                throw failure;
            }
        }
        if (editKind != EditKind.BINDING
                || !(state instanceof NodeMenuState.DirectBindingEdit editState)
                || topologyEdit == null
                || linkedNetworkId == null) {
            return invalid(request);
        }
        UUID tunnelId = editState.tunnel().tunnelId();
        UUID channelId = editState.channel().channelId();
        NetworkTopologyService.Edit edit = topologyEdit;
        service.topology().removeDirectBinding(player, edit, editState.channel().channelId());
        clearEdit();
        state = service.channelRoot(player, linkedNetworkId, nodeId, tunnelId, channelId);
        return response(player, request);
    }

    private NodeMenuResponse beginCreateChannel(ServerPlayer player, NodeMenuRequest.BeginCreateChannel request) {
        if (!(state instanceof NodeMenuState.DirectChannelList list)
                || linkedNetworkId == null
                || editKind != EditKind.NONE) {
            return invalid(request);
        }
        UUID tunnelId = list.tunnel().tunnelId();
        NetworkTopologyService.Edit edit =
                service.topology().acquireChannelCollection(player, linkedNetworkId, tunnelId);
        try {
            state = service.channelCreateEdit(
                    player, linkedNetworkId, nodeId, tunnelId, new ManagedNamePrefix(request.suggestionPrefix()));
            topologyEdit = edit;
            editNetworkId = linkedNetworkId;
            editKind = EditKind.CHANNEL;
            return response(player, request);
        } catch (RuntimeException failure) {
            safeCancel(player, edit);
            throw failure;
        }
    }

    private NodeMenuResponse beginRenameChannel(ServerPlayer player, NodeMenuRequest.BeginRenameChannel request) {
        if (!(state instanceof NodeMenuState.DirectChannelSettings settings)
                || linkedNetworkId == null
                || editKind != EditKind.NONE
                || !settings.channel().channelId().equals(request.channelId())) {
            return invalid(request);
        }
        UUID tunnelId = settings.tunnel().tunnelId();
        NetworkTopologyService.Edit edit =
                service.topology().acquireChannel(player, linkedNetworkId, request.channelId());
        try {
            state = service.channelRenameEdit(player, linkedNetworkId, nodeId, tunnelId, request.channelId());
            topologyEdit = edit;
            editNetworkId = linkedNetworkId;
            editKind = EditKind.CHANNEL;
            return response(player, request);
        } catch (RuntimeException failure) {
            safeCancel(player, edit);
            throw failure;
        }
    }

    private NodeMenuResponse saveChannel(ServerPlayer player, NodeMenuRequest.SaveChannel request) {
        if (editKind != EditKind.CHANNEL
                || !(state instanceof NodeMenuState.DirectChannelEdit editState)
                || topologyEdit == null
                || linkedNetworkId == null) {
            return invalid(request);
        }
        ManagedName name;
        try {
            name = new ManagedName(request.name());
        } catch (IllegalArgumentException invalidName) {
            return failure(request, NodeMenuResponse.Reason.INVALID_NAME, state);
        }
        UUID tunnelId = editState.tunnel().tunnelId();
        UUID channelId =
                editState.existing() == null ? null : editState.existing().channelId();
        NetworkTopologyService.Edit edit = topologyEdit;
        if (edit.kind() == NetworkTopologyService.Kind.CHANNEL_COLLECTION && editState.existing() == null) {
            service.topology().createChannel(player, edit, name);
        } else if (edit.kind() == NetworkTopologyService.Kind.CHANNEL && editState.existing() != null) {
            service.topology().renameChannel(player, edit, name);
        } else {
            return invalid(request);
        }
        clearEdit();
        state = channelId == null
                ? service.tunnelState(player, linkedNetworkId, nodeId, tunnelId, null, false)
                : service.channelRoot(player, linkedNetworkId, nodeId, tunnelId, channelId);
        return response(player, request);
    }

    private NodeMenuResponse requestDeleteChannel(ServerPlayer player, NodeMenuRequest.RequestDeleteChannel request) {
        if (!(state instanceof NodeMenuState.DirectChannelSettings settings)
                || linkedNetworkId == null
                || editKind != EditKind.NONE
                || !settings.channel().channelId().equals(request.channelId())) {
            return invalid(request);
        }
        UUID tunnelId = settings.tunnel().tunnelId();
        NetworkTopologyService.DeletionEdit deletion =
                service.topology().beginChannelDeletion(player, linkedNetworkId, request.channelId());
        try {
            state = service.channelDelete(player, linkedNetworkId, nodeId, tunnelId, deletion);
            channelDeletion = deletion;
            editNetworkId = linkedNetworkId;
            editKind = EditKind.CHANNEL_DELETE;
            return response(player, request);
        } catch (RuntimeException failure) {
            safeCancel(player, deletion.edit());
            throw failure;
        }
    }

    private NodeMenuResponse confirmDeleteChannel(ServerPlayer player, NodeMenuRequest request) {
        if (editKind != EditKind.CHANNEL_DELETE
                || !(state instanceof NodeMenuState.DirectChannelDelete deleteState)
                || channelDeletion == null
                || linkedNetworkId == null) {
            return invalid(request);
        }
        UUID tunnelId = deleteState.tunnel().tunnelId();
        service.topology().confirmChannelDeletion(player, channelDeletion);
        clearEdit();
        state = service.tunnelState(player, linkedNetworkId, nodeId, tunnelId, null, false);
        return response(player, request);
    }

    private NodeMenuResponse openDomain(ServerPlayer player, NodeMenuRequest request) {
        NodeMenuNodeSummary node = nodeSummaryOrNull();
        if (editKind != EditKind.NONE || linkedNetworkId == null || node == null) {
            return invalid(request);
        }
        if (!node.enabled()) {
            return failure(request, NodeMenuResponse.Reason.NODE_DISABLED, state);
        }
        state = service.domainRoot(player, linkedNetworkId, nodeId);
        return response(player, request);
    }

    private NodeMenuResponse beginDomainEdit(ServerPlayer player, NodeMenuRequest request) {
        if (!(state instanceof NodeMenuState.DomainRoot) || linkedNetworkId == null || editKind != EditKind.NONE) {
            return invalid(request);
        }
        NetworkTopologyService.Edit edit = service.topology().acquireNode(player, linkedNetworkId, nodeId);
        try {
            state = service.domainEdit(player, linkedNetworkId, nodeId);
            topologyEdit = edit;
            editNetworkId = linkedNetworkId;
            editKind = EditKind.DOMAIN;
            return response(player, request);
        } catch (RuntimeException failure) {
            safeCancel(player, edit);
            throw failure;
        }
    }

    private NodeMenuResponse setDomainDirection(ServerPlayer player, NodeMenuRequest.SetDomainDirection request) {
        if (editKind != EditKind.DOMAIN
                || !(state instanceof NodeMenuState.DomainEdit)
                || topologyEdit == null
                || linkedNetworkId == null) {
            return invalid(request);
        }
        NetworkTopologyService.Edit edit = topologyEdit;
        service.topology().setDomainConfiguration(player, edit, request.direction(), request.confirmedReset());
        clearEdit();
        state = service.domainRoot(player, linkedNetworkId, nodeId);
        return response(player, request);
    }

    private NodeMenuResponse removeDomain(ServerPlayer player, NodeMenuRequest request) {
        if (editKind != EditKind.DOMAIN
                || !(state instanceof NodeMenuState.DomainEdit)
                || topologyEdit == null
                || linkedNetworkId == null) {
            return invalid(request);
        }
        NetworkTopologyService.Edit edit = topologyEdit;
        service.topology().removeDomainConfiguration(player, edit);
        clearEdit();
        state = service.domainRoot(player, linkedNetworkId, nodeId);
        return response(player, request);
    }

    private @Nullable NodeMenuResponse heartbeat(ServerPlayer player, NodeMenuRequest request) {
        if (editKind == EditKind.NONE) {
            return invalid(request);
        }
        if (channelDeletion != null) {
            service.topology().heartbeat(player, channelDeletion.edit());
        } else if (tunnelSwitchEdit != null) {
            service.topology().heartbeat(player, tunnelSwitchEdit.edit());
        } else if (topologyEdit != null) {
            service.topology().heartbeat(player, topologyEdit);
        } else {
            service.management().heartbeat(player, requireEditToken());
        }
        return null;
    }

    private NodeMenuResponse cancelEdit(ServerPlayer player, NodeMenuRequest request) {
        if (editKind == EditKind.NONE) {
            return invalid(request);
        }
        return back(player, request);
    }

    private NodeMenuResponse rejected(
            ServerPlayer player, NodeMenuRequest request, NodeManagementService.Reason reason) {
        NodeMenuResponse.Reason mapped =
                switch (reason) {
                    case NO_ACCESS -> NodeMenuResponse.Reason.NO_ACCESS;
                    case UNAVAILABLE -> NodeMenuResponse.Reason.UNAVAILABLE;
                    case OUT_OF_RANGE -> NodeMenuResponse.Reason.OUT_OF_RANGE;
                    case LOCKED -> NodeMenuResponse.Reason.LOCKED;
                    case LOCK_EXPIRED -> NodeMenuResponse.Reason.LOCK_EXPIRED;
                    case STALE_REVISION -> NodeMenuResponse.Reason.STALE_REVISION;
                    case NAME_CONFLICT -> NodeMenuResponse.Reason.NAME_CONFLICT;
                    case NODE_DISABLED -> NodeMenuResponse.Reason.NODE_DISABLED;
                    case RESET_REQUIRED -> NodeMenuResponse.Reason.RESET_REQUIRED;
                };
        return rejected(player, request, mapped);
    }

    private NodeMenuResponse rejected(
            ServerPlayer player, NodeMenuRequest request, NetworkTopologyService.Reason reason) {
        NodeMenuResponse.Reason mapped =
                switch (reason) {
                    case NO_ACCESS -> NodeMenuResponse.Reason.NO_ACCESS;
                    case UNAVAILABLE -> NodeMenuResponse.Reason.UNAVAILABLE;
                    case LOCKED -> NodeMenuResponse.Reason.LOCKED;
                    case LOCK_EXPIRED -> NodeMenuResponse.Reason.LOCK_EXPIRED;
                    case STALE_REVISION -> NodeMenuResponse.Reason.STALE_REVISION;
                    case NAME_CONFLICT -> NodeMenuResponse.Reason.NAME_CONFLICT;
                    case QUOTA_REACHED -> NodeMenuResponse.Reason.QUOTA_REACHED;
                    case NODE_DISABLED -> NodeMenuResponse.Reason.NODE_DISABLED;
                    case TUNNEL_DISABLED -> NodeMenuResponse.Reason.TUNNEL_DISABLED;
                    case LAST_CHANNEL -> NodeMenuResponse.Reason.LAST_CHANNEL;
                    case RESET_REQUIRED -> NodeMenuResponse.Reason.RESET_REQUIRED;
                    case TUNNEL_SWITCH_REQUIRED -> NodeMenuResponse.Reason.TUNNEL_SWITCH_REQUIRED;
                };
        return rejected(player, request, mapped);
    }

    private NodeMenuResponse rejected(ServerPlayer player, NodeMenuRequest request, NodeMenuResponse.Reason mapped) {
        boolean retain = (mapped == NodeMenuResponse.Reason.NAME_CONFLICT && editKind != EditKind.NONE)
                || (mapped == NodeMenuResponse.Reason.RESET_REQUIRED
                        && (editKind == EditKind.MODE || editKind == EditKind.BINDING || editKind == EditKind.DOMAIN))
                || (mapped == NodeMenuResponse.Reason.QUOTA_REACHED && editKind == EditKind.BINDING)
                || (mapped == NodeMenuResponse.Reason.QUOTA_REACHED && editKind == EditKind.CHANNEL)
                || (mapped == NodeMenuResponse.Reason.LAST_CHANNEL && editKind == EditKind.NONE)
                || (mapped == NodeMenuResponse.Reason.LOCKED && editKind == EditKind.NONE);
        if (retain) {
            return failure(request, mapped, state);
        }
        safeCancel(player);
        if (mapped == NodeMenuResponse.Reason.NO_ACCESS) {
            state = new NodeMenuState.NoAccess();
            linkedNetworkId = null;
        } else if (mapped == NodeMenuResponse.Reason.UNAVAILABLE || mapped == NodeMenuResponse.Reason.OUT_OF_RANGE) {
            state = new NodeMenuState.Unavailable();
            linkedNetworkId = null;
        } else {
            refreshRoot(player);
        }
        return failure(request, mapped, state);
    }

    private void refreshRoot(ServerPlayer player) {
        NodeMenuService.Initial initial = service.initial(player, nodePosition);
        if (!initial.nodeId().equals(nodeId)) {
            state = new NodeMenuState.Unavailable();
            linkedNetworkId = null;
            return;
        }
        state = initial.state();
        linkedNetworkId = initial.linkedNetworkId();
    }

    private void refreshBlankLink(ServerPlayer player) {
        if (editKind != EditKind.NONE
                || (!(state instanceof NodeMenuState.BlankList) && !(state instanceof NodeMenuState.NoNetworks))) {
            return;
        }
        NodeMenuService.Initial initial = service.initial(player, nodePosition);
        if (initial.nodeId().equals(nodeId) && initial.linkedNetworkId() != null) {
            state = initial.state();
            linkedNetworkId = initial.linkedNetworkId();
        }
    }

    private void safeCancel(ServerPlayer player) {
        NetworkTopologyService.DeletionEdit activeDeletion = channelDeletion;
        if (activeDeletion != null) {
            safeCancel(player, activeDeletion.edit());
        }
        NetworkTopologyService.Edit activeTopologyEdit = topologyEdit;
        if (activeTopologyEdit != null && activeDeletion == null) {
            safeCancel(player, activeTopologyEdit);
        }
        EditLockTable.Token token = editToken;
        if (token != null && activeTopologyEdit == null && activeDeletion == null) {
            safeCancel(player, token);
        }
        clearEdit();
    }

    private void safeCancel(ServerPlayer player, EditLockTable.Token token) {
        try {
            service.management().cancel(player, token);
        } catch (RuntimeException ignored) {
            // The authority service may already have released an invalid or expired token.
        }
    }

    private void safeCancel(ServerPlayer player, NetworkTopologyService.Edit edit) {
        try {
            service.topology().cancel(player, edit);
        } catch (RuntimeException ignored) {
            // The topology service may already have released an invalid or expired edit.
        }
    }

    private void clearEdit() {
        cancelTransfer();
        editToken = null;
        editNetworkId = null;
        editRevision = -1;
        topologyEdit = null;
        channelDeletion = null;
        tunnelSwitchEdit = null;
        networkMoveEdit = null;
        editKind = EditKind.NONE;
    }

    private EditLockTable.Token requireEditToken() {
        return Objects.requireNonNull(editToken, "editToken");
    }

    private UUID requireEditNetwork() {
        return Objects.requireNonNull(editNetworkId, "editNetworkId");
    }

    private @Nullable NodeMenuNodeSummary nodeSummaryOrNull() {
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
            case NodeMenuState.Unavailable ignored -> null;
            case NodeMenuState.NoAccess ignored -> null;
            case NodeMenuState.NoNetworks ignored -> null;
            case NodeMenuState.BlankList ignored -> null;
            case NodeMenuState.BlankEdit ignored -> null;
        };
    }

    private NodeMenuResponse response(ServerPlayer player, NodeMenuRequest request) {
        var response = new NodeMenuResponse.State(containerId, sessionId, request.sequence(), state);
        if (state instanceof NodeMenuState.DirectBindingEdit edit
                && edit.policy() != null
                && io.github.loongin.omniresonance.networking.NodePolicyFrames.responseSize(response) > 262144) {
            if (transfer != null) throw new IllegalStateException("Transfer already active");
            service.topology()
                    .validateBindingEdit(player, topologyEdit, edit.channel().channelId());
            UUID id = UUID.randomUUID();
            int length = io.github.loongin.omniresonance.networking.ResourcePolicyEditCodec.encodedSize(edit.policy());
            long now = service.currentTick();
            service.transfers()
                    .beginDownload(
                            playerId,
                            sessionId,
                            id,
                            length,
                            now,
                            () -> io.github.loongin.omniresonance.networking.ResourcePolicyEditCodec.encode(
                                    edit.policy()));
            transfer = new PolicyTransfer(
                    id,
                    request.sequence(),
                    now + 200,
                    topologyEdit,
                    edit.channel().channelId(),
                    edit.tunnel().tunnelId(),
                    length,
                    null);
            return new NodeMenuResponse.Download(
                    containerId, sessionId, request.sequence(), edit.withPolicy(null), id, length);
        }
        return response;
    }

    private NodeMenuResponse.Failure invalid(NodeMenuRequest request) {
        return failure(request, NodeMenuResponse.Reason.INVALID_REQUEST, state);
    }

    private NodeMenuResponse.Failure failure(
            NodeMenuRequest request, NodeMenuResponse.Reason reason, @Nullable NodeMenuState latestState) {
        return new NodeMenuResponse.Failure(
                request.containerId(),
                request.sessionId(),
                request.sequence(),
                reason,
                latestState instanceof NodeMenuState.DirectBindingEdit edit ? edit.withPolicy(null) : latestState);
    }

    private enum EditKind {
        NONE,
        BLANK,
        RENAME,
        MODE,
        NETWORK_MOVE,
        BINDING,
        DOMAIN,
        CHANNEL,
        CHANNEL_DELETE,
        TUNNEL_SWITCH
    }
}
