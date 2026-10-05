// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import io.github.loongin.omniresonance.filter.ItemFilterService;
import io.github.loongin.omniresonance.network.DirectNodeBinding;
import io.github.loongin.omniresonance.network.ManagedNamePrefix;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.network.NetworkTopologyIndex;
import io.github.loongin.omniresonance.network.NetworkTopologyService;
import io.github.loongin.omniresonance.networking.FilterPresetPage;
import io.github.loongin.omniresonance.networking.FilterPresetSummary;
import io.github.loongin.omniresonance.networking.NodeChannelPage;
import io.github.loongin.omniresonance.networking.NodeChannelSummary;
import io.github.loongin.omniresonance.networking.NodeFacePreview;
import io.github.loongin.omniresonance.networking.NodeMenuNodeSummary;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import io.github.loongin.omniresonance.networking.NodeNetworkPage;
import io.github.loongin.omniresonance.networking.NodeNetworkSummary;
import io.github.loongin.omniresonance.networking.NodeTransferStatus;
import io.github.loongin.omniresonance.networking.NodeTunnelPage;
import io.github.loongin.omniresonance.networking.NodeTunnelSummary;
import io.github.loongin.omniresonance.networking.NodeTunnelSwitchSummary;
import io.github.loongin.omniresonance.networking.TopologyDeletionSummary;
import io.github.loongin.omniresonance.transfer.ResourceDirectScheduler;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Runtime-scoped physical-node Menu opener and bounded initial-state mapper.
 *
 * <p>The service shares the existing management authority and network directory; it creates no index, lock table or
 * global Menu collection. Accepted saves retain only one bounded exact-connection context until completion,
 * disconnect or timeout. Opening and helpers run on the matching server thread and never load a chunk,
 * retain a block entity, save synchronously to disk, simulate transfer or access client classes.
 */
public final class NodeMenuService implements AutoCloseable {
    private static final int PAGE_SIZE = 128;
    /** One accepted save per connection, bounded by the shared transfer pool; cleared on completion/logout/stop. */
    private final java.util.Map<UUID, SubmittedNodePolicySave> submittedSaves = new java.util.HashMap<>();

    private final io.github.loongin.omniresonance.networking.ManagementTransferPool transfers =
            new io.github.loongin.omniresonance.networking.ManagementTransferPool();
    private @Nullable MinecraftServer server;
    private @Nullable NodeManagementService management;
    private @Nullable NetworkTopologyService topology;
    private @Nullable NetworkDirectory networks;
    private @Nullable Supplier<UUID> sessionIds;
    private @Nullable ItemFilterService filters;
    private @Nullable BiFunction<UUID, UUID, ResourceDirectScheduler.Status> directStatus;
    private @Nullable BiFunction<UUID, UUID, io.github.loongin.omniresonance.networking.NodeDomainStatus> domainStatus;

    /** Installs the owning-server read-only domain status provider; it must not activate storage or call capabilities. */
    private @Nullable java.util.function.Function<
                    UUID, io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator.Status>
            chunkStatus;

    public void installChunkStatus(
            java.util.function.Function<UUID, io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator.Status>
                    provider) {
        requireServerThread();
        chunkStatus = Objects.requireNonNull(provider);
    }

    /** Reads existing scheduler diagnostics without activating storage or invoking transfer work; server thread only. */
    public boolean directFailed(UUID node, UUID channel) {
        requireServerThread();
        return directStatus != null && directStatus.apply(node, channel) == ResourceDirectScheduler.Status.FAILED;
    }
    /** Reads existing domain diagnostics without granting access or mutating runtime state; server thread only. */
    public boolean domainFailed(UUID network, UUID node) {
        requireServerThread();
        if (domainStatus == null) return false;
        var value = domainStatus.apply(network, node);
        return value == io.github.loongin.omniresonance.networking.NodeDomainStatus.FAILED
                || value == io.github.loongin.omniresonance.networking.NodeDomainStatus.STORAGE_UNAVAILABLE
                || value == io.github.loongin.omniresonance.networking.NodeDomainStatus.FILTER_BLOCKED;
    }

    public void installDomainStatus(
            BiFunction<UUID, UUID, io.github.loongin.omniresonance.networking.NodeDomainStatus> provider) {
        requireServerThread();
        domainStatus = Objects.requireNonNull(provider);
    }

    /** Retains one server lifecycle and already-composed collaborators without reading world state. */
    public NodeMenuService(
            MinecraftServer server,
            NodeManagementService management,
            NetworkTopologyService topology,
            NetworkDirectory networks,
            Supplier<UUID> sessionIds) {
        this(
                server,
                management,
                topology,
                networks,
                sessionIds,
                null,
                (node, channel) -> ResourceDirectScheduler.Status.IDLE);
    }

    /** Retains server-owned filter and status readers for this Menu lifecycle; reads never invoke transfer work. */
    public NodeMenuService(
            MinecraftServer server,
            NodeManagementService management,
            NetworkTopologyService topology,
            NetworkDirectory networks,
            Supplier<UUID> sessionIds,
            @Nullable ItemFilterService filters,
            BiFunction<UUID, UUID, ResourceDirectScheduler.Status> directStatus) {
        this.filters = filters;
        this.directStatus = Objects.requireNonNull(directStatus);
        this.server = Objects.requireNonNull(server, "server");
        requireServerThread();
        this.management = Objects.requireNonNull(management, "management");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.networks = Objects.requireNonNull(networks, "networks");
        this.sessionIds = Objects.requireNonNull(sessionIds, "sessionIds");
    }

    /**
     * Opens one authoritative zero-slot Menu and writes only position/session client bootstrap data.
     * Invalid physical interaction returns false without changing the player's current Menu.
     */
    public boolean open(ServerPlayer player, BlockPos position) {
        requirePlayer(player);
        Objects.requireNonNull(position, "position");
        if (player.serverLevel().getBlockState(position).getBlock() instanceof AbstractResonanceNodeBlock block
                && block.form() == NodeForm.AE_INTERFACE) return false;
        UUID sessionId = Objects.requireNonNull(sessionIds().get(), "sessionId");
        Initial initial = initial(player, position);
        SimpleMenuProvider provider = new SimpleMenuProvider(
                (containerId, inventory, actualPlayer) -> {
                    if (actualPlayer != player) {
                        return null;
                    }
                    return new ResonanceNodeMenu(containerId, inventory, this, position, sessionId, initial);
                },
                net.minecraft.network.chat.Component.empty());
        OptionalInt opened = player.openMenu(provider, buffer -> {
            buffer.writeBlockPos(position);
            buffer.writeUUID(sessionId);
            buffer.writeBoolean(false);
        });
        if (opened.isPresent()
                && player.containerMenu instanceof ResonanceNodeMenu menu
                && menu.sessionId().equals(sessionId)) {
            PacketDistributor.sendToPlayer(player, menu.initialResponse());
        }
        return opened.isPresent();
    }

    /** Opens only an existing node configuration, reusing native policy editing and its node lease. */
    public void openExistingConfiguration(ServerPlayer player, UUID network, UUID nodeId, @Nullable UUID channel) {
        requirePlayer(player);
        var node = management().inspectLinked(player, network, nodeId);
        if (!node.enabled()) throw new IllegalStateException("Disabled node");
        if (channel == null) {
            if (node.mode() != io.github.loongin.omniresonance.node.NodeMode.DOMAIN
                    || topology()
                            .inspectDomainConfiguration(player, network, nodeId)
                            .isEmpty()) throw new IllegalStateException("Existing domain configuration required");
        } else {
            var tunnel = topology().directTunnelId(player, network, nodeId).orElseThrow();
            if (topology()
                            .inspectNodeChannel(player, network, nodeId, tunnel, channel)
                            .currentDirection()
                    == null) throw new IllegalStateException("Existing binding required");
        }
        var edit = topology().acquireNode(player, network, nodeId);
        boolean opened = false;
        try {
            NodeMenuState.ResourceEdit state = channel == null
                    ? domainEdit(player, network, nodeId)
                    : bindingEdit(
                            player,
                            network,
                            nodeId,
                            topology().directTunnelId(player, network, nodeId).orElseThrow(),
                            channel);
            UUID session = sessionIds().get();
            var initial = new Initial(nodeId, network, state);
            var result = player.openMenu(
                    new SimpleMenuProvider(
                            (id, inventory, actor) -> {
                                if (actor != player) return null;
                                var menu = new ResonanceNodeMenu(
                                        id, inventory, this, node.position().pos(), session, initial);
                                menu.configureRemote(edit, state);
                                return menu;
                            },
                            net.minecraft.network.chat.Component.empty()),
                    buffer -> {
                        buffer.writeBlockPos(node.position().pos());
                        buffer.writeUUID(session);
                        buffer.writeBoolean(true);
                    });
            opened = result.isPresent();
            if (!opened) throw new IllegalStateException("Configuration menu opening was cancelled");
            if (opened && player.containerMenu instanceof ResonanceNodeMenu menu)
                PacketDistributor.sendToPlayer(player, menu.remoteInitialResponse(player));
        } finally {
            if (!opened) topology().cancel(player, edit);
        }
    }

    /** Creates one server Menu for real lifecycle/GameTest callers without sending a client open packet. */
    ResonanceNodeMenu createMenu(int containerId, ServerPlayer player, BlockPos position, UUID sessionId) {
        requirePlayer(player);
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(sessionId, "sessionId");
        return new ResonanceNodeMenu(
                containerId, player.getInventory(), this, position, sessionId, initial(player, position));
    }

    public io.github.loongin.omniresonance.networking.ManagementTransferPool transfers() {
        requireServerThread();
        return transfers;
    }

    void beginSubmittedSave(
            ServerPlayer player,
            ResonanceNodeMenu menu,
            io.github.loongin.omniresonance.networking.NodeMenuRequest.BeginPolicyUpload request,
            NetworkTopologyService.Edit edit,
            NodeMenuState.ResourceEdit metadata) {
        requirePlayer(player);
        if (submittedSaves.containsKey(player.getUUID()))
            throw new IllegalStateException("A node save is already pending");
        long now = currentTick();
        var save =
                new SubmittedNodePolicySave(player, menu.sessionId(), menu.containerId, request, edit, metadata, now);
        save.validate(this, now);
        transfers.beginUpload(player.getUUID(), menu.sessionId(), request.transfer(), request.length(), now);
        submittedSaves.put(player.getUUID(), save);
    }

    boolean saving(UUID player, UUID session) {
        var save = submittedSaves.get(player);
        return save != null && save.session.equals(session);
    }

    boolean matchesSubmitted(
            ServerPlayer player, io.github.loongin.omniresonance.networking.ManagementTransferMessage message) {
        var save = submittedSaves.get(player.getUUID());
        return save != null && save.matches(player, message);
    }

    /**
     * Consumes a previously authorized exact connection/session/transfer on the owning server thread.
     * Unknown frames return false without mutation; accepted frames may commit only on a validated Finish.
     * Sends one terminal response and releases ownership on completion/failure. Does not load chunks or retry.
     */
    public boolean handleSubmittedTransfer(
            ServerPlayer player, io.github.loongin.omniresonance.networking.ManagementTransferMessage message) {
        requirePlayer(player);
        if (!matchesSubmitted(player, message)) return false;
        var response = receiveSubmittedTransfer(player, message);
        if (response != null) PacketDistributor.sendToPlayer(player, response);
        return true;
    }

    @Nullable
    io.github.loongin.omniresonance.networking.NodeMenuResponse receiveSubmittedTransfer(
            ServerPlayer player, io.github.loongin.omniresonance.networking.ManagementTransferMessage message) {
        requirePlayer(player);
        var save = submittedSaves.get(player.getUUID());
        if (save == null || !save.matches(player, message)) return null;
        var response = save.accept(this, message);
        if (response != null) {
            submittedSaves.remove(player.getUUID(), save);
            if (player.containerMenu instanceof ResonanceNodeMenu menu
                    && menu.sessionId().equals(save.session)) menu.completeSubmittedSave(response);
        }
        return response;
    }

    void validateSubmittedPhysical(NodeMenuNodeSummary node) {
        var dimension = net.minecraft.resources.ResourceKey.create(Registries.DIMENSION, node.dimension());
        var level = server.getLevel(dimension);
        if (level == null) throw new IllegalStateException("Saved node dimension is unavailable");
        var pos = node.position();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        // Do not load distant chunks. Persistent authority was just validated, as for remote configuration.
        if (chunk == null) return;
        var entity = chunk.getBlockEntity(pos);
        if (!(entity instanceof ResonanceNodeBlockEntity physical)
                || physical.state().isEmpty()
                || !physical.state().orElseThrow().nodeId().equals(node.nodeId())
                || !(physical.getBlockState().getBlock() instanceof AbstractResonanceNodeBlock block)
                || block.form() != node.form()
                || physical.getBlockState().getValue(AbstractResonanceNodeBlock.FACING) != node.facing())
            throw new IllegalStateException("Submitted node identity changed");
    }

    void tickSubmittedSaves(
            long now,
            java.util.function.BiConsumer<ServerPlayer, io.github.loongin.omniresonance.networking.NodeMenuResponse>
                    sender) {
        var iterator = submittedSaves.values().iterator();
        while (iterator.hasNext()) {
            var save = iterator.next();
            try {
                save.validate(this, now);
            } catch (RuntimeException invalid) {
                iterator.remove();
                save.cancel(this);
                var failure = save.failure(
                        io.github.loongin.omniresonance.networking.NodeMenuResponse.Reason.INVALID_REQUEST);
                if (save.player.containerMenu instanceof ResonanceNodeMenu menu
                        && menu.sessionId().equals(save.session)) menu.completeSubmittedSave(failure);
                sender.accept(save.player, failure);
            }
        }
    }

    long currentTick() {
        requireServerThread();
        return server.overworld().getGameTime();
    }

    /** Releases a disconnected menu's transfer and all remaining per-player reservations. */
    public void disconnect(ServerPlayer player) {
        requirePlayer(player);
        if (player.containerMenu instanceof ResonanceNodeMenu menu) menu.cancelTransfer();
        var save = submittedSaves.get(player.getUUID());
        if (save != null && save.player != player) return;
        if (save != null) {
            submittedSaves.remove(player.getUUID());
            save.cancel(this);
        }
        transfers.disconnect(player.getUUID());
    }

    /** Sends at most one fragment per connected active menu and releases expired transport storage. */
    public void tick() {
        requireServerThread();
        long now = currentTick();
        tickSubmittedSaves(now, PacketDistributor::sendToPlayer);
        transfers.expire(now);
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (player.containerMenu instanceof ResonanceNodeMenu menu) {
                menu.transferTick(player, now);
                if (chunkStatus != null
                        && now % 20 == 0
                        && menu.linkedNetworkId().isPresent()
                        && canKeepOpen(player, menu)) {
                    var node = management()
                            .inspectLinked(player, menu.linkedNetworkId().orElseThrow(), menu.nodeId());
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                            player,
                            new io.github.loongin.omniresonance.networking.NodeChunkStatus(
                                    menu.containerId,
                                    menu.sessionId(),
                                    node.nodeId(),
                                    node.revision(),
                                    chunkStatus.apply(node.nodeId())));
                }
            }
    }

    boolean canKeepOpen(ServerPlayer player, ResonanceNodeMenu menu) {
        requirePlayer(player);
        if (menu.remoteConfiguration()) {
            try {
                return management()
                        .inspectLinked(player, menu.linkedNetworkId().orElseThrow(), menu.nodeId())
                        .enabled();
            } catch (RuntimeException unavailable) {
                return false;
            }
        }
        return management()
                .canKeepPhysicalMenuOpen(
                        player,
                        menu.nodePosition(),
                        menu.nodeId(),
                        menu.linkedNetworkId().orElse(null));
    }

    void closeMenu(ResonanceNodeMenu menu) {
        Objects.requireNonNull(menu, "menu");
        if (server != null) {
            requireServerThread();
        }
        if (!saving(menu.playerId(), menu.sessionId())) transfers.cancelSession(menu.playerId(), menu.sessionId());
        menu.markClosed();
    }

    /** Closes only this player's physical node menu using the revoked network; native removal cancels its exact edits. */
    public void revokeNetworkAccess(ServerPlayer player, UUID networkId) {
        requirePlayer(player);
        Objects.requireNonNull(networkId, "networkId");
        if (player.containerMenu instanceof ResonanceNodeMenu menu && menu.referencesNetwork(networkId)) {
            player.closeContainer();
        }
    }

    /** Closes every currently connected node Menu referencing one deleted network without retaining a Menu index. */
    public void revokeDeletedNetwork(UUID networkId) {
        Objects.requireNonNull(networkId, "networkId");
        MinecraftServer activeServer = Objects.requireNonNull(server, "closed node menu service");
        requireServerThread();
        for (ServerPlayer player : List.copyOf(activeServer.getPlayerList().getPlayers())) {
            revokeNetworkAccess(player, networkId);
        }
    }

    /** Releases lifecycle collaborators; repeated close is a no-op and no player Menu is retained here. */
    @Override
    public void close() {
        MinecraftServer activeServer = server;
        if (activeServer == null) {
            return;
        }
        if (!activeServer.isSameThread()) {
            throw new IllegalStateException("Node Menu service closed outside the server thread");
        }
        for (ServerPlayer player : activeServer.getPlayerList().getPlayers())
            if (player.containerMenu instanceof ResonanceNodeMenu menu) menu.cancelTransfer();
        for (var save : submittedSaves.values()) save.cancel(this);
        submittedSaves.clear();
        transfers.close();
        server = null;
        management = null;
        topology = null;
        networks = null;
        sessionIds = null;
        filters = null;
        directStatus = null;
        domainStatus = null;
        chunkStatus = null;
    }

    Initial initial(ServerPlayer player, BlockPos position) {
        UUID nodeId = resolveNodeId(player, position);
        try {
            NodeManagementService.PhysicalAccess access = management().inspectPhysical(player, position);
            if (access instanceof NodeManagementService.BlankAccess blank) {
                NodeNetworkPage page = page(player, null, false);
                NodeMenuState state =
                        page.totalCount() == 0 ? new NodeMenuState.NoNetworks() : new NodeMenuState.BlankList(page);
                return new Initial(nodeId, null, state);
            }
            NodeManagementService.LinkedAccess linked = (NodeManagementService.LinkedAccess) access;
            return new Initial(nodeId, linked.network().id(), route(player, linked.network(), linked.node()));
        } catch (NodeManagementService.Rejected rejected) {
            NodeMenuState state = rejected.reason() == NodeManagementService.Reason.NO_ACCESS
                    ? new NodeMenuState.NoAccess()
                    : new NodeMenuState.Unavailable();
            return new Initial(nodeId, null, state);
        } catch (NetworkTopologyService.Rejected rejected) {
            NodeMenuState state = rejected.reason() == NetworkTopologyService.Reason.NO_ACCESS
                    ? new NodeMenuState.NoAccess()
                    : new NodeMenuState.Unavailable();
            return new Initial(nodeId, null, state);
        }
    }

    NodeNetworkPage page(ServerPlayer player, @Nullable UUID anchor, boolean backwards) {
        NetworkDirectory.AccessPage accessible =
                networks().pageAccessible(player.getUUID(), anchor, backwards, PAGE_SIZE);
        List<NodeNetworkSummary> entries = new ArrayList<>(accessible.entries().size());
        for (NetworkMetadata metadata : accessible.entries()) {
            NodeNetworkSummary.Role role = metadata.ownerId().equals(player.getUUID())
                    ? NodeNetworkSummary.Role.OWNER
                    : NodeNetworkSummary.Role.ADMIN;
            entries.add(new NodeNetworkSummary(metadata.id(), metadata.name().value(), role));
        }
        return new NodeNetworkPage(entries, accessible.totalCount(), accessible.hasPrevious(), accessible.hasNext());
    }

    NodeNetworkSummary networkSummary(ServerPlayer player, UUID networkId) {
        requirePlayer(player);
        NetworkMetadata metadata = networks()
                .find(Objects.requireNonNull(networkId, "networkId"))
                .orElseThrow(() -> new IllegalArgumentException("Node Menu network is unavailable"));
        NodeNetworkSummary.Role role;
        if (metadata.ownerId().equals(player.getUUID())) {
            role = NodeNetworkSummary.Role.OWNER;
        } else if (metadata.administrators().contains(player.getUUID())) {
            role = NodeNetworkSummary.Role.ADMIN;
        } else {
            throw new IllegalArgumentException("Node Menu network is inaccessible");
        }
        return new NodeNetworkSummary(metadata.id(), metadata.name().value(), role);
    }

    NodeMenuState blankRoot(ServerPlayer player) {
        NodeNetworkPage page = page(player, null, false);
        return page.totalCount() == 0 ? new NodeMenuState.NoNetworks() : new NodeMenuState.BlankList(page);
    }

    NodeMenuState.LinkedRoot linkedRoot(ServerPlayer player, UUID networkId, UUID nodeId) {
        return new NodeMenuState.LinkedRoot(linkedSummary(player, networkId, nodeId));
    }

    NodeMenuState.ModeRoot modeRoot(ServerPlayer player, UUID networkId, UUID nodeId) {
        return new NodeMenuState.ModeRoot(linkedSummary(player, networkId, nodeId));
    }

    NodeMenuState.NetworkSelection networkSelection(
            ServerPlayer player, UUID networkId, UUID nodeId, @Nullable UUID anchor, boolean backwards) {
        return new NodeMenuState.NetworkSelection(
                linkedSummary(player, networkId, nodeId), page(player, anchor, backwards));
    }

    NodeMenuState.NetworkMoveEdit networkMoveEdit(
            ServerPlayer player, UUID sourceNetworkId, UUID nodeId, UUID targetNetworkId) {
        return new NodeMenuState.NetworkMoveEdit(
                linkedSummary(player, sourceNetworkId, nodeId), networkSummary(player, targetNetworkId));
    }

    NodeMenuState.DirectTunnelList directTunnels(
            ServerPlayer player, UUID networkId, UUID nodeId, @Nullable UUID anchor, boolean backwards) {
        NetworkTopologyService.NodeTunnelBatch batch =
                topology().pageNodeTunnels(player, networkId, nodeId, anchor, backwards);
        NetworkTopologyIndex.Page<NetworkTopologyService.NodeTunnelView> page = batch.page();
        List<NodeTunnelSummary> entries = new ArrayList<>(page.entries().size());
        for (NetworkTopologyService.NodeTunnelView view : page.entries()) {
            entries.add(tunnelSummary(view));
        }
        return new NodeMenuState.DirectTunnelList(
                linkedSummary(player, networkId, nodeId),
                new NodeTunnelPage(entries, page.totalCount(), page.hasPrevious(), page.hasNext()),
                batch.revision());
    }

    NodeMenuState tunnelState(
            ServerPlayer player, UUID networkId, UUID nodeId, UUID tunnelId, @Nullable UUID anchor, boolean backwards) {
        NetworkTopologyService.NodeTunnelView tunnel =
                topology().inspectNodeTunnel(player, networkId, nodeId, tunnelId);
        NodeTunnelSummary summary = tunnelSummary(tunnel);
        if (!summary.enabled()) {
            return new NodeMenuState.RestrictedTunnel(linkedSummary(player, networkId, nodeId), summary);
        }
        NetworkTopologyIndex.Page<NetworkTopologyService.NodeChannelView> page =
                topology().pageNodeChannels(player, networkId, nodeId, tunnelId, anchor, backwards);
        List<NodeChannelSummary> entries = new ArrayList<>(page.entries().size());
        for (NetworkTopologyService.NodeChannelView view : page.entries()) {
            entries.add(channelSummary(view));
        }
        return new NodeMenuState.DirectChannelList(
                linkedSummary(player, networkId, nodeId),
                summary,
                new NodeChannelPage(entries, page.totalCount(), page.hasPrevious(), page.hasNext()));
    }

    NodeMenuState directRoute(ServerPlayer player, UUID networkId, UUID nodeId) {
        Optional<UUID> tunnelId = topology().directTunnelId(player, networkId, nodeId);
        return tunnelId.isEmpty()
                ? directTunnels(player, networkId, nodeId, null, false)
                : tunnelState(player, networkId, nodeId, tunnelId.orElseThrow(), null, false);
    }

    NodeMenuState.DirectChannelRoot channelRoot(
            ServerPlayer player, UUID networkId, UUID nodeId, UUID tunnelId, UUID channelId) {
        NetworkTopologyService.NodeTunnelView tunnel =
                topology().inspectNodeTunnel(player, networkId, nodeId, tunnelId);
        NetworkTopologyService.NodeChannelView channel =
                topology().inspectNodeChannel(player, networkId, nodeId, tunnelId, channelId);
        var summary = channelSummary(channel);
        var binding = summary.currentDirection() == null
                ? null
                : topology().inspectDirectBinding(player, networkId, nodeId, channelId);
        NodeMenuNodeSummary node = linkedSummary(player, networkId, nodeId);
        NodeTransferStatus status = binding != null && binding.workingFaces().effectiveMask(node.facing()) == 0
                ? NodeTransferStatus.NO_WORK_FACES
                : NodeTransferStatus.valueOf(Objects.requireNonNull(directStatus)
                        .apply(nodeId, channelId)
                        .name());
        return new NodeMenuState.DirectChannelRoot(
                node,
                tunnelSummary(tunnel),
                summary,
                binding == null
                        ? null
                        : io.github.loongin.omniresonance.networking.NodeResourcePolicySummary.from(
                                binding.storedPolicy()),
                status);
    }

    NodeMenuState.DirectChannelSettings channelSettings(
            ServerPlayer player, UUID networkId, UUID nodeId, UUID tunnelId, UUID channelId) {
        NetworkTopologyService.NodeTunnelView tunnel =
                topology().inspectNodeTunnel(player, networkId, nodeId, tunnelId);
        NetworkTopologyService.NodeChannelView channel =
                topology().inspectNodeChannel(player, networkId, nodeId, tunnelId, channelId);
        return new NodeMenuState.DirectChannelSettings(
                linkedSummary(player, networkId, nodeId), tunnelSummary(tunnel), channelSummary(channel));
    }

    NodeMenuState.DirectTunnelSwitch tunnelSwitchState(
            ServerPlayer player, UUID networkId, UUID nodeId, NetworkTopologyService.TunnelSwitchEdit switchEdit) {
        Objects.requireNonNull(switchEdit, "switchEdit");
        return new NodeMenuState.DirectTunnelSwitch(
                linkedSummary(player, networkId, nodeId),
                new NodeTunnelSwitchSummary(
                        switchEdit.targetTunnelId(),
                        switchEdit.targetName().value(),
                        switchEdit.removedBindingCount()));
    }

    NodeMenuState.DirectBindingEdit bindingEdit(
            ServerPlayer player, UUID networkId, UUID nodeId, UUID tunnelId, UUID channelId) {
        return bindingEdit(player, networkId, nodeId, tunnelId, channelId, 0);
    }

    NodeMenuState.DirectBindingEdit bindingEdit(
            ServerPlayer player, UUID networkId, UUID nodeId, UUID tunnelId, UUID channelId, int offset) {
        return bindingEdit(player, networkId, nodeId, tunnelId, channelId, offset, "", -1);
    }

    NodeMenuState.DirectBindingEdit bindingEdit(
            ServerPlayer player,
            UUID networkId,
            UUID nodeId,
            UUID tunnelId,
            UUID channelId,
            int offset,
            String query,
            long libraryRevision) {
        NetworkTopologyService.NodeTunnelView tunnel =
                topology().inspectNodeTunnel(player, networkId, nodeId, tunnelId);
        NetworkTopologyService.NodeChannelView channel =
                topology().inspectNodeChannel(player, networkId, nodeId, tunnelId, channelId);
        DirectNodeBinding binding = topology().inspectDirectBinding(player, networkId, nodeId, channelId);
        ResourceTransferPolicy policy = binding.policy();
        FilterPresetPage page = filters == null
                ? new FilterPresetPage(List.of(), 0, 0, 0)
                : filters.page(player, networkId, offset, query, libraryRevision);
        FilterPresetSummary selected = filters == null || policy.filterPresetId() == null
                ? null
                : filters.summary(player, networkId, policy.filterPresetId());
        NodeMenuNodeSummary node = linkedSummary(player, networkId, nodeId);
        return new NodeMenuState.DirectBindingEdit(
                node,
                tunnelSummary(tunnel),
                channelSummary(channel),
                ResourcePolicyEdit.fromStored(binding.storedPolicy()),
                page,
                selected == null ? null : selected.name(),
                binding.workingFaces(),
                facePreviews(node));
    }

    private List<NodeFacePreview> facePreviews(NodeMenuNodeSummary node) {
        ServerLevel level =
                Objects.requireNonNull(server).getLevel(ResourceKey.create(Registries.DIMENSION, node.dimension()));
        return NodeFacePreviews.collect(
                node.position(),
                node.form(),
                node.facing(),
                target -> level != null
                        && level.getChunkSource().getChunkNow(target.getX() >> 4, target.getZ() >> 4) != null,
                target -> Objects.requireNonNull(Objects.requireNonNull(level)
                                .getChunkSource()
                                .getChunkNow(target.getX() >> 4, target.getZ() >> 4))
                        .getBlockState(target));
    }

    NodeMenuState.DirectChannelEdit channelCreateEdit(
            ServerPlayer player, UUID networkId, UUID nodeId, UUID tunnelId, ManagedNamePrefix suggestionPrefix) {
        NetworkTopologyService.NodeTunnelView tunnel =
                topology().inspectNodeTunnel(player, networkId, nodeId, tunnelId);
        if (!tunnel.tunnel().tunnel().enabled()) {
            throw new IllegalArgumentException("Disabled tunnel cannot create a channel");
        }
        return new NodeMenuState.DirectChannelEdit(
                linkedSummary(player, networkId, nodeId),
                tunnelSummary(tunnel),
                null,
                topology()
                        .suggestedChannelName(player, networkId, tunnelId, suggestionPrefix)
                        .value());
    }

    NodeMenuState.DirectChannelEdit channelRenameEdit(
            ServerPlayer player, UUID networkId, UUID nodeId, UUID tunnelId, UUID channelId) {
        NetworkTopologyService.NodeTunnelView tunnel =
                topology().inspectNodeTunnel(player, networkId, nodeId, tunnelId);
        NetworkTopologyService.NodeChannelView channel =
                topology().inspectNodeChannel(player, networkId, nodeId, tunnelId, channelId);
        return new NodeMenuState.DirectChannelEdit(
                linkedSummary(player, networkId, nodeId), tunnelSummary(tunnel), channelSummary(channel), null);
    }

    NodeMenuState.DirectChannelDelete channelDelete(
            ServerPlayer player,
            UUID networkId,
            UUID nodeId,
            UUID tunnelId,
            NetworkTopologyService.DeletionEdit deletion) {
        NetworkTopologyService.NodeTunnelView tunnel =
                topology().inspectNodeTunnel(player, networkId, nodeId, tunnelId);
        UUID channelId = Objects.requireNonNull(deletion.edit().channelId(), "channelId");
        NetworkTopologyService.NodeChannelView channel =
                topology().inspectNodeChannel(player, networkId, nodeId, tunnelId, channelId);
        return new NodeMenuState.DirectChannelDelete(
                linkedSummary(player, networkId, nodeId),
                tunnelSummary(tunnel),
                new TopologyDeletionSummary(
                        TopologyDeletionSummary.Kind.CHANNEL,
                        channelId,
                        channel.channel().channel().name().value(),
                        0,
                        deletion.impact().bindingCount()));
    }

    NodeMenuState.DomainRoot domainRoot(ServerPlayer player, UUID networkId, UUID nodeId) {
        var existing =
                topology().inspectDomainConfiguration(player, networkId, nodeId).orElse(null);
        var status = existing == null
                ? io.github.loongin.omniresonance.networking.NodeDomainStatus.UNCONFIGURED
                : !existing.configured()
                        ? io.github.loongin.omniresonance.networking.NodeDomainStatus.PENDING
                        : domainStatus == null
                                ? io.github.loongin.omniresonance.networking.NodeDomainStatus.IDLE
                                : domainStatus.apply(networkId, nodeId);
        return new NodeMenuState.DomainRoot(
                linkedSummary(player, networkId, nodeId), existing == null ? null : existing.direction(), status);
    }

    NodeMenuState.DomainEdit domainEdit(ServerPlayer player, UUID networkId, UUID nodeId) {
        return domainEdit(player, networkId, nodeId, 0, "", -1);
    }

    NodeMenuState.DomainEdit domainEdit(
            ServerPlayer player, UUID networkId, UUID nodeId, int offset, String query, long libraryRevision) {
        var existing =
                topology().inspectDomainConfiguration(player, networkId, nodeId).orElse(null);
        NodeMenuNodeSummary node = linkedSummary(player, networkId, nodeId);
        var stored = existing == null
                ? new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(
                        ResourceTransferPolicy.defaults(
                                io.github.loongin.omniresonance.network.TransferDirection.INPUT),
                        java.util.Map.of())
                : existing.storedPolicy();
        FilterPresetPage page = filters == null
                ? new FilterPresetPage(List.of(), 0, 0, 0)
                : filters.page(player, networkId, offset, query, libraryRevision);
        var selectedId = stored.effectivePolicy().filterPresetId();
        FilterPresetSummary selected =
                filters == null || selectedId == null ? null : filters.summary(player, networkId, selectedId);
        return new NodeMenuState.DomainEdit(
                node,
                existing == null ? null : existing.direction(),
                ResourcePolicyEdit.fromStored(stored),
                page,
                selected == null ? null : selected.name(),
                existing == null
                        ? node.form() == io.github.loongin.omniresonance.node.NodeForm.PANEL
                                ? io.github.loongin.omniresonance.network.WorkingFaces.attachedFace()
                                : io.github.loongin.omniresonance.network.WorkingFaces.explicit(0)
                        : existing.workingFaces(),
                facePreviews(node));
    }

    NodeMenuState.LinkedRename linkedRename(ServerPlayer player, UUID networkId, UUID nodeId) {
        return new NodeMenuState.LinkedRename(linkedSummary(player, networkId, nodeId));
    }

    NodeMenuState.LinkedMode linkedMode(ServerPlayer player, UUID networkId, UUID nodeId) {
        return new NodeMenuState.LinkedMode(linkedSummary(player, networkId, nodeId));
    }

    /** Refreshes header authority without navigating away from a valid browse page or replacing its cursor. */
    NodeMenuState refreshBrowseHeader(ServerPlayer player, UUID networkId, UUID nodeId, NodeMenuState previous) {
        var node = linkedSummary(player, networkId, nodeId);
        if (previous instanceof NodeMenuState.DomainRoot
                && node.mode() != io.github.loongin.omniresonance.node.NodeMode.DOMAIN)
            return linkedRoute(player, networkId, nodeId);
        if (!(previous instanceof NodeMenuState.ModeRoot
                        || previous instanceof NodeMenuState.LinkedRoot
                        || previous instanceof NodeMenuState.NetworkSelection
                        || previous instanceof NodeMenuState.DomainRoot)
                && node.mode() != io.github.loongin.omniresonance.node.NodeMode.DIRECT)
            return linkedRoute(player, networkId, nodeId);
        return switch (previous) {
            case NodeMenuState.LinkedRoot ignored -> new NodeMenuState.LinkedRoot(node);
            case NodeMenuState.ModeRoot ignored -> new NodeMenuState.ModeRoot(node);
            case NodeMenuState.NetworkSelection view -> new NodeMenuState.NetworkSelection(node, view.page());
            case NodeMenuState.DirectTunnelList view ->
                new NodeMenuState.DirectTunnelList(node, view.page(), view.revision());
            case NodeMenuState.RestrictedTunnel view -> new NodeMenuState.RestrictedTunnel(node, view.tunnel());
            case NodeMenuState.DirectChannelList view ->
                new NodeMenuState.DirectChannelList(node, view.tunnel(), view.page());
            case NodeMenuState.DirectChannelRoot view ->
                new NodeMenuState.DirectChannelRoot(
                        node, view.tunnel(), view.channel(), view.policy(), view.transferStatus());
            case NodeMenuState.DirectChannelSettings view ->
                new NodeMenuState.DirectChannelSettings(node, view.tunnel(), view.channel());
            case NodeMenuState.DomainRoot view -> new NodeMenuState.DomainRoot(node, view.direction(), view.status());
            default -> throw new IllegalStateException("Header refresh requires a browse page");
        };
    }

    NodeMenuState linkedRoute(ServerPlayer player, UUID networkId, UUID nodeId) {
        NetworkNodeRecord node = management().inspectLinked(player, networkId, nodeId);
        NetworkMetadata network = networks()
                .find(networkId)
                .orElseThrow(() -> new IllegalArgumentException("Node Menu network is unavailable"));
        return route(player, network, node);
    }

    NodeManagementService management() {
        requireServerThread();
        return Objects.requireNonNull(management, "Node Menu service is closed");
    }

    NetworkTopologyService topology() {
        requireServerThread();
        return Objects.requireNonNull(topology, "Node Menu service is closed");
    }

    private UUID resolveNodeId(ServerPlayer player, BlockPos position) {
        if (player.distanceToSqr(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5) > 64.0) {
            throw new IllegalArgumentException("Node Menu position is out of range");
        }
        LevelChunk chunk =
                player.serverLevel().getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
        if (chunk == null || !(chunk.getBlockState(position).getBlock() instanceof AbstractResonanceNodeBlock)) {
            throw new IllegalArgumentException("Node Menu physical block is unavailable");
        }
        BlockEntity blockEntity = chunk.getBlockEntity(position);
        if (!(blockEntity instanceof ResonanceNodeBlockEntity node)) {
            throw new IllegalArgumentException("Node Menu block entity is unavailable");
        }
        return node.state()
                .orElseThrow(() -> new IllegalArgumentException("Node Menu identity is unavailable"))
                .nodeId();
    }

    private NodeMenuNodeSummary linkedSummary(ServerPlayer player, UUID networkId, UUID nodeId) {
        NetworkNodeRecord node = management().inspectLinked(player, networkId, nodeId);
        NetworkMetadata network = networks()
                .find(networkId)
                .orElseThrow(() -> new IllegalArgumentException("Node Menu network is unavailable"));
        return nodeSummary(network, node);
    }

    private NodeMenuState route(ServerPlayer player, NetworkMetadata network, NetworkNodeRecord node) {
        if (!node.enabled() || node.mode() == NodeMode.UNCONFIGURED) {
            return new NodeMenuState.ModeRoot(nodeSummary(network, node));
        }
        return switch (node.mode()) {
            case UNCONFIGURED -> throw new IllegalStateException("Unreachable node route");
            case DIRECT -> directRoute(player, network.id(), node.nodeId());
            case DOMAIN -> domainRoot(player, network.id(), node.nodeId());
        };
    }

    private static NodeTunnelSummary tunnelSummary(NetworkTopologyService.NodeTunnelView view) {
        NetworkTopologyService.TunnelView tunnel = view.tunnel();
        return new NodeTunnelSummary(
                tunnel.tunnel().tunnelId(),
                tunnel.tunnel().name().value(),
                tunnel.tunnel().revision(),
                tunnel.tunnel().enabled(),
                tunnel.channelCount(),
                tunnel.bindingCount(),
                view.currentNodeBindingCount());
    }

    private static NodeChannelSummary channelSummary(NetworkTopologyService.NodeChannelView view) {
        NetworkTopologyService.ChannelView channel = view.channel();
        return new NodeChannelSummary(
                channel.channel().channelId(),
                channel.channel().name().value(),
                channel.channel().revision(),
                channel.inputCount(),
                channel.outputCount(),
                view.currentDirection());
    }

    private static NodeMenuNodeSummary nodeSummary(NetworkMetadata network, NetworkNodeRecord node) {
        return new NodeMenuNodeSummary(
                network.id(),
                network.name().value(),
                node.nodeId(),
                node.name().value(),
                node.revision(),
                node.position().dimension().location(),
                node.position().pos(),
                node.form(),
                node.facing(),
                node.enabled(),
                node.chunkLoadingRequested(),
                node.mode());
    }

    private void requirePlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        requireServerThread();
        if (player.server != server) {
            throw new IllegalArgumentException("Node Menu player belongs to another server");
        }
    }

    private void requireServerThread() {
        MinecraftServer activeServer = server;
        if (activeServer == null) {
            throw new IllegalStateException("Node Menu service is closed");
        }
        if (!activeServer.isSameThread()) {
            throw new IllegalStateException("Node Menu service accessed outside the server thread");
        }
    }

    private NetworkDirectory networks() {
        requireServerThread();
        return Objects.requireNonNull(networks, "Node Menu service is closed");
    }

    private Supplier<UUID> sessionIds() {
        requireServerThread();
        return Objects.requireNonNull(sessionIds, "Node Menu service is closed");
    }

    record Initial(UUID nodeId, @Nullable UUID linkedNetworkId, NodeMenuState state) {
        Initial {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(state, "state");
        }
    }
}
