// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.config.ServerConfig;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.node.NetworkNodeDirectory;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.persistence.DomainStorage;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.security.EditLockTable;
import io.github.loongin.omniresonance.security.NetworkPermissions;
import io.github.loongin.omniresonance.transfer.ItemTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.StoredResourcePolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread authority for topology roles, quotas, revisions and edit leases. Persistent mutations remain in
 * {@link NetworkSavedData}; this service performs no world scan, capability access, simulation or synchronous save.
 */
public final class NetworkTopologyService implements AutoCloseable {
    public enum Reason {
        NO_ACCESS,
        UNAVAILABLE,
        LOCKED,
        LOCK_EXPIRED,
        STALE_REVISION,
        NAME_CONFLICT,
        QUOTA_REACHED,
        NODE_DISABLED,
        TUNNEL_DISABLED,
        LAST_CHANNEL,
        RESET_REQUIRED,
        TUNNEL_SWITCH_REQUIRED
    }

    public enum Kind {
        TUNNEL_COLLECTION,
        CHANNEL_COLLECTION,
        TUNNEL,
        CHANNEL,
        NODE
    }

    /** Expected request rejection containing only one stable internal category. */
    public static final class Rejected extends IllegalStateException {
        private final Reason reason;

        private Rejected(Reason reason) {
            super(Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** Immutable server-issued edit context; possession never replaces sender, role or revision validation. */
    public record Edit(
            EditLockTable.Token token,
            Kind kind,
            UUID networkId,
            @Nullable UUID tunnelId,
            @Nullable UUID channelId,
            @Nullable UUID nodeId,
            long revision,
            long topologyRevision) {
        public Edit {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(networkId, "networkId");
            if (revision < -1 || topologyRevision < 0) {
                throw new IllegalArgumentException("Invalid topology edit revision");
            }
        }
    }

    /** Exact deletion impact and lease captured together; confirm still rechecks every authoritative value. */
    public record DeletionEdit(Edit edit, TopologyDeletionImpact impact) {
        public DeletionEdit {
            Objects.requireNonNull(edit, "edit");
            Objects.requireNonNull(impact, "impact");
        }
    }

    /** Server-owned node lease plus the exact target and impact of a pending direct-tunnel switch. */
    public record TunnelSwitchEdit(
            Edit edit, UUID targetTunnelId, ManagedName targetName, int removedBindingCount, long topologyRevision) {
        public TunnelSwitchEdit {
            Objects.requireNonNull(edit, "edit");
            Objects.requireNonNull(targetTunnelId, "targetTunnelId");
            Objects.requireNonNull(targetName, "targetName");
            if (edit.kind() != Kind.NODE
                    || removedBindingCount < 1
                    || topologyRevision < 0
                    || edit.topologyRevision() != topologyRevision) {
                throw new IllegalArgumentException("Invalid direct tunnel switch edit");
            }
        }
    }

    public record TunnelView(NetworkTunnelRecord tunnel, int channelCount, int bindingCount) {
        public TunnelView {
            Objects.requireNonNull(tunnel, "tunnel");
            if (channelCount < 0 || bindingCount < 0) {
                throw new IllegalArgumentException("Invalid tunnel view counts");
            }
        }
    }

    public record ChannelView(NetworkChannelRecord channel, int inputCount, int outputCount) {
        public ChannelView {
            Objects.requireNonNull(channel, "channel");
            if (inputCount < 0 || outputCount < 0) {
                throw new IllegalArgumentException("Invalid channel view counts");
            }
        }
    }

    public record NodeTunnelView(TunnelView tunnel, int currentNodeBindingCount) {
        public NodeTunnelView {
            Objects.requireNonNull(tunnel, "tunnel");
            if (currentNodeBindingCount < 0 || currentNodeBindingCount > tunnel.bindingCount()) {
                throw new IllegalArgumentException("Invalid current-node tunnel binding count");
            }
        }
    }

    /** One owning-thread batch and its directory revision, without retaining mutable network data. */
    public record NodeTunnelBatch(long revision, NetworkTopologyIndex.Page<NodeTunnelView> page) {
        public NodeTunnelBatch {
            Objects.requireNonNull(page, "page");
            if (revision < 0) {
                throw new IllegalArgumentException("Negative node tunnel batch revision");
            }
        }
    }

    public record NodeChannelView(
            ChannelView channel, @Nullable TransferDirection currentDirection) {
        public NodeChannelView {
            Objects.requireNonNull(channel, "channel");
        }
    }

    private @Nullable MinecraftServer server;
    private @Nullable NetworkDirectory networks;
    private @Nullable SavedNetworkRepository repository;
    private @Nullable NetworkNodeDirectory nodes;
    private @Nullable EditLockTable locks;
    private @Nullable Supplier<UUID> objectIds;
    private final long configurationEpoch;
    private final boolean configurationLoaded;
    private ServerSettings settings;
    private long configurationRevision;
    private long currentTick;

    /** Retains matching runtime collaborators and validated settings without reading or creating network data. */
    public NetworkTopologyService(
            MinecraftServer server,
            NetworkDirectory networks,
            SavedNetworkRepository repository,
            NetworkNodeDirectory nodes,
            EditLockTable locks,
            ServerConfig.State initialConfig,
            Supplier<UUID> objectIds) {
        this.server = Objects.requireNonNull(server, "server");
        requireServerThread();
        this.networks = Objects.requireNonNull(networks, "networks");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.objectIds = Objects.requireNonNull(objectIds, "objectIds");
        Objects.requireNonNull(initialConfig, "initialConfig");
        if (initialConfig.epoch() < 0 || initialConfig.revision() < 0) {
            throw new IllegalArgumentException("Invalid configuration identity");
        }
        configurationEpoch = initialConfig.epoch();
        configurationLoaded = initialConfig.loaded();
        configurationRevision = initialConfig.revision();
        settings = initialConfig.loaded()
                ? Objects.requireNonNull(initialConfig.settings(), "settings")
                : ServerSettings.defaults();
    }

    /** Returns the current immutable quota snapshot on the server thread without mutation or simulation. */
    public ServerSettings settingsSnapshot() {
        requireServerThread();
        return settings;
    }

    /**
     * Returns a known storage failure on the server thread after checking access. This read does not activate
     * buckets, mutate inventory, or prove that an inactive domain is healthy; denied access is rejected normally.
     */
    public boolean domainStorageUnavailable(ServerPlayer actor, UUID networkId) {
        requireNetwork(actor, networkId);
        return repository().domainStorage(networkId).state() == DomainStorage.State.UNAVAILABLE;
    }

    public NetworkMetadata inspectNetwork(ServerPlayer actor, UUID networkId) {
        return requireNetwork(actor, networkId).metadata();
    }

    /**
     * Returns one authorized DIRECT node's derived tunnel without acquiring a lock or mutating authority.
     * This server-thread query returns empty for an unbound DIRECT node; non-DIRECT nodes, missing authority,
     * inaccessible networks and wrong-thread access reject.
     */
    public Optional<UUID> directTunnelId(ServerPlayer actor, UUID networkId, UUID nodeId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkNodeRecord node = requireNode(networkId, Objects.requireNonNull(nodeId, "nodeId"), network);
        if (node.mode() != NodeMode.DIRECT) {
            throw rejected(Reason.UNAVAILABLE);
        }
        return network.directTunnelId(nodeId);
    }

    public long suggestedTunnelNumber(ServerPlayer actor, UUID networkId) {
        return Math.incrementExact(requireNetwork(actor, networkId).lastTunnelNumber());
    }

    public long suggestedChannelNumber(ServerPlayer actor, UUID networkId, UUID tunnelId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        return Math.incrementExact(network.findTunnel(Objects.requireNonNull(tunnelId, "tunnelId"))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE))
                .lastChannelNumber());
    }

    /** Returns a nonreserving localized tunnel display-name suggestion after current role validation. */
    public ManagedName suggestedTunnelName(ServerPlayer actor, UUID networkId, ManagedNamePrefix prefix) {
        return requireNetwork(actor, networkId).suggestedTunnelName(Objects.requireNonNull(prefix, "prefix"));
    }

    /** Returns a nonreserving localized channel display-name suggestion after current role validation. */
    public ManagedName suggestedChannelName(
            ServerPlayer actor, UUID networkId, UUID tunnelId, ManagedNamePrefix prefix) {
        return requireNetwork(actor, networkId)
                .suggestedChannelName(
                        Objects.requireNonNull(tunnelId, "tunnelId"), Objects.requireNonNull(prefix, "prefix"));
    }

    public NetworkTopologyIndex.Page<TunnelView> pageTunnels(
            ServerPlayer actor, UUID networkId, @Nullable UUID anchor, boolean backwards) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkTopologyIndex.Page<NetworkTunnelRecord> page = network.pageTunnels(anchor, backwards, 128);
        List<TunnelView> entries = new java.util.ArrayList<>(page.entries().size());
        for (NetworkTunnelRecord tunnel : page.entries()) {
            entries.add(new TunnelView(
                    tunnel, network.channelCount(tunnel.tunnelId()), network.bindingCountForTunnel(tunnel.tunnelId())));
        }
        return new NetworkTopologyIndex.Page<>(entries, page.totalCount(), page.hasPrevious(), page.hasNext());
    }

    /** Pages an authorized node's directory on the server thread without performing client text matching. */
    public NodeTunnelBatch pageNodeTunnels(
            ServerPlayer actor, UUID networkId, UUID nodeId, @Nullable UUID anchor, boolean backwards) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkNodeRecord node = requireNode(networkId, Objects.requireNonNull(nodeId, "nodeId"), network);
        requireEnabled(node);
        if (node.mode() != NodeMode.DIRECT) {
            throw rejected(Reason.UNAVAILABLE);
        }
        Map<UUID, Integer> currentCounts = new HashMap<>();
        for (DirectNodeBinding binding : network.directBindings(nodeId)) {
            NetworkChannelRecord channel =
                    network.findChannel(binding.channelId()).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
            currentCounts.merge(channel.tunnelId(), 1, Math::addExact);
        }
        NetworkTopologyIndex.Page<NetworkTunnelRecord> page = network.pageTunnels(anchor, backwards, 128);
        List<NodeTunnelView> entries = new java.util.ArrayList<>(page.entries().size());
        for (NetworkTunnelRecord tunnel : page.entries()) {
            entries.add(new NodeTunnelView(
                    new TunnelView(
                            tunnel,
                            network.channelCount(tunnel.tunnelId()),
                            network.bindingCountForTunnel(tunnel.tunnelId())),
                    currentCounts.getOrDefault(tunnel.tunnelId(), 0)));
        }
        return new NodeTunnelBatch(
                network.topologyRevision(),
                new NetworkTopologyIndex.Page<>(entries, page.totalCount(), page.hasPrevious(), page.hasNext()));
    }

    public NetworkTopologyIndex.Page<ChannelView> pageChannels(
            ServerPlayer actor, UUID networkId, UUID tunnelId, @Nullable UUID anchor, boolean backwards) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkTunnelRecord tunnel = network.findTunnel(Objects.requireNonNull(tunnelId, "tunnelId"))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        if (!tunnel.enabled()) {
            throw rejected(Reason.TUNNEL_DISABLED);
        }
        NetworkTopologyIndex.Page<NetworkChannelRecord> page = network.pageChannels(tunnelId, anchor, backwards, 128);
        List<ChannelView> entries = new java.util.ArrayList<>(page.entries().size());
        for (NetworkChannelRecord channel : page.entries()) {
            NetworkTopologyIndex.DirectionCounts counts = network.channelDirectionCounts(channel.channelId());
            entries.add(new ChannelView(channel, counts.inputs(), counts.outputs()));
        }
        return new NetworkTopologyIndex.Page<>(entries, page.totalCount(), page.hasPrevious(), page.hasNext());
    }

    /** Pages one enabled direct node's channels within one enabled tunnel and includes only its exact direction. */
    public NetworkTopologyIndex.Page<NodeChannelView> pageNodeChannels(
            ServerPlayer actor, UUID networkId, UUID nodeId, UUID tunnelId, @Nullable UUID anchor, boolean backwards) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkNodeRecord node = requireNode(networkId, Objects.requireNonNull(nodeId, "nodeId"), network);
        requireEnabled(node);
        if (node.mode() != NodeMode.DIRECT) {
            throw rejected(Reason.UNAVAILABLE);
        }
        NetworkTunnelRecord tunnel = network.findTunnel(Objects.requireNonNull(tunnelId, "tunnelId"))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        if (!tunnel.enabled()) {
            throw rejected(Reason.TUNNEL_DISABLED);
        }
        Map<UUID, TransferDirection> currentDirections = new HashMap<>();
        for (DirectNodeBinding binding : network.directBindings(nodeId)) {
            currentDirections.put(binding.channelId(), binding.direction());
        }
        NetworkTopologyIndex.Page<NetworkChannelRecord> page = network.pageChannels(tunnelId, anchor, backwards, 128);
        List<NodeChannelView> entries = new java.util.ArrayList<>(page.entries().size());
        for (NetworkChannelRecord channel : page.entries()) {
            NetworkTopologyIndex.DirectionCounts counts = network.channelDirectionCounts(channel.channelId());
            entries.add(new NodeChannelView(
                    new ChannelView(channel, counts.inputs(), counts.outputs()),
                    currentDirections.get(channel.channelId())));
        }
        return new NetworkTopologyIndex.Page<>(entries, page.totalCount(), page.hasPrevious(), page.hasNext());
    }

    public TunnelView inspectTunnel(ServerPlayer actor, UUID networkId, UUID tunnelId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkTunnelRecord tunnel = network.findTunnel(Objects.requireNonNull(tunnelId, "tunnelId"))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        return new TunnelView(tunnel, network.channelCount(tunnelId), network.bindingCountForTunnel(tunnelId));
    }

    /** Inspects one tunnel and the selected direct node's aggregate participation without mutation. */
    public NodeTunnelView inspectNodeTunnel(ServerPlayer actor, UUID networkId, UUID nodeId, UUID tunnelId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkNodeRecord node = requireNode(networkId, Objects.requireNonNull(nodeId, "nodeId"), network);
        requireEnabled(node);
        if (node.mode() != NodeMode.DIRECT) {
            throw rejected(Reason.UNAVAILABLE);
        }
        NetworkTunnelRecord tunnel = network.findTunnel(Objects.requireNonNull(tunnelId, "tunnelId"))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        int currentCount = 0;
        for (DirectNodeBinding binding : network.directBindings(nodeId)) {
            NetworkChannelRecord channel =
                    network.findChannel(binding.channelId()).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
            if (channel.tunnelId().equals(tunnelId)) {
                currentCount = Math.incrementExact(currentCount);
            }
        }
        return new NodeTunnelView(
                new TunnelView(tunnel, network.channelCount(tunnelId), network.bindingCountForTunnel(tunnelId)),
                currentCount);
    }

    public ChannelView inspectChannel(ServerPlayer actor, UUID networkId, UUID channelId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkChannelRecord channel = network.findChannel(Objects.requireNonNull(channelId, "channelId"))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        NetworkTopologyIndex.DirectionCounts counts = network.channelDirectionCounts(channelId);
        return new ChannelView(channel, counts.inputs(), counts.outputs());
    }

    /** Inspects one direct node/channel relation without acquiring or modifying an edit lease. */
    public NodeChannelView inspectNodeChannel(
            ServerPlayer actor, UUID networkId, UUID nodeId, UUID tunnelId, UUID channelId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkNodeRecord node = requireNode(networkId, Objects.requireNonNull(nodeId, "nodeId"), network);
        requireEnabled(node);
        if (node.mode() != NodeMode.DIRECT) {
            throw rejected(Reason.UNAVAILABLE);
        }
        NetworkChannelRecord channel = network.findChannel(Objects.requireNonNull(channelId, "channelId"))
                .filter(candidate -> candidate.tunnelId().equals(Objects.requireNonNull(tunnelId, "tunnelId")))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        NetworkTopologyIndex.DirectionCounts counts = network.channelDirectionCounts(channelId);
        TransferDirection direction = network.directBindings(nodeId).stream()
                .filter(binding -> binding.channelId().equals(channelId))
                .map(DirectNodeBinding::direction)
                .findFirst()
                .orElse(null);
        return new NodeChannelView(new ChannelView(channel, counts.inputs(), counts.outputs()), direction);
    }

    /** Reads the unique domain direction for one authorized domain node without locking or mutation. */
    public Optional<TransferDirection> inspectDomainDirection(ServerPlayer actor, UUID networkId, UUID nodeId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkNodeRecord node = requireNode(networkId, Objects.requireNonNull(nodeId, "nodeId"), network);
        if (node.mode() != NodeMode.DOMAIN) {
            throw rejected(Reason.UNAVAILABLE);
        }
        return network.domainConfiguration(nodeId).map(DomainNodeConfiguration::direction);
    }

    /** Returns authorized domain configuration without locking, mutation or world discovery. */
    public Optional<DomainNodeConfiguration> inspectDomainConfiguration(
            ServerPlayer actor, UUID networkId, UUID nodeId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkNodeRecord node = requireNode(networkId, nodeId, network);
        requireEnabled(node);
        if (node.mode() != NodeMode.DOMAIN) throw rejected(Reason.UNAVAILABLE);
        return network.domainConfiguration(nodeId);
    }

    public Edit acquireTunnelCollection(ServerPlayer actor, UUID networkId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        return acquire(
                actor,
                Kind.TUNNEL_COLLECTION,
                networkId,
                null,
                null,
                null,
                -1,
                network.topologyRevision(),
                new TopologyEditKey.TunnelCollection(networkId).lockId());
    }

    public Edit acquireChannelCollection(ServerPlayer actor, UUID networkId, UUID tunnelId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkTunnelRecord tunnel = network.findTunnel(Objects.requireNonNull(tunnelId, "tunnelId"))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        if (!tunnel.enabled()) {
            throw rejected(Reason.TUNNEL_DISABLED);
        }
        return acquire(
                actor,
                Kind.CHANNEL_COLLECTION,
                networkId,
                tunnelId,
                null,
                null,
                tunnel.revision(),
                network.topologyRevision(),
                new TopologyEditKey.ChannelCollection(networkId, tunnelId).lockId());
    }

    public Edit acquireTunnel(ServerPlayer actor, UUID networkId, UUID tunnelId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkTunnelRecord tunnel = network.findTunnel(Objects.requireNonNull(tunnelId, "tunnelId"))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        return acquire(
                actor,
                Kind.TUNNEL,
                networkId,
                tunnelId,
                null,
                null,
                tunnel.revision(),
                network.topologyRevision(),
                new TopologyEditKey.Tunnel(networkId, tunnelId).lockId());
    }

    public Edit acquireChannel(ServerPlayer actor, UUID networkId, UUID channelId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkChannelRecord channel = network.findChannel(Objects.requireNonNull(channelId, "channelId"))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        return acquire(
                actor,
                Kind.CHANNEL,
                networkId,
                channel.tunnelId(),
                channelId,
                null,
                channel.revision(),
                network.topologyRevision(),
                new TopologyEditKey.Channel(networkId, channelId).lockId());
    }

    public Edit acquireNode(ServerPlayer actor, UUID networkId, UUID nodeId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkNodeRecord node = requireNode(networkId, Objects.requireNonNull(nodeId, "nodeId"), network);
        return acquire(
                actor,
                Kind.NODE,
                networkId,
                null,
                null,
                nodeId,
                node.revision(),
                network.topologyRevision(),
                new TopologyEditKey.Node(networkId, nodeId).lockId());
    }

    /**
     * Acquires one node lease and returns a non-mutating direct-tunnel switch preview.
     * The preview is server-thread-owned and never authorizes confirmation by itself; disabled/same/missing targets,
     * unbound or non-DIRECT nodes, role loss and lock conflicts reject without changing topology or dirty state.
     */
    public TunnelSwitchEdit requestTunnelSwitch(ServerPlayer actor, UUID networkId, UUID nodeId, UUID targetTunnelId) {
        Objects.requireNonNull(targetTunnelId, "targetTunnelId");
        Edit edit = acquireNode(actor, networkId, nodeId);
        try {
            NetworkSavedData network = requireEdit(actor, edit, Kind.NODE);
            NetworkNodeRecord node = currentNode(network, edit);
            requireEnabled(node);
            if (node.mode() != NodeMode.DIRECT) {
                throw rejected(Reason.UNAVAILABLE);
            }
            UUID currentTunnel =
                    network.directTunnelId(node.nodeId()).orElseThrow(() -> rejected(Reason.TUNNEL_SWITCH_REQUIRED));
            NetworkTunnelRecord target =
                    network.findTunnel(targetTunnelId).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
            if (currentTunnel.equals(target.tunnelId())) {
                throw rejected(Reason.TUNNEL_SWITCH_REQUIRED);
            }
            if (!target.enabled()) {
                throw rejected(Reason.TUNNEL_DISABLED);
            }
            int removedBindingCount = network.directBindings(node.nodeId()).size();
            return new TunnelSwitchEdit(
                    edit, target.tunnelId(), target.name(), removedBindingCount, network.topologyRevision());
        } catch (RuntimeException failure) {
            releaseQuietly(actor, edit);
            throw failure;
        }
    }

    public NetworkSavedData.TunnelCreation createTunnel(
            ServerPlayer actor, Edit edit, ManagedName name, ManagedName initialChannelName) {
        NetworkSavedData network = requireEdit(actor, edit, Kind.TUNNEL_COLLECTION);
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(initialChannelName, "initialChannelName");
        int limit = settings.tunnelsPerNetwork();
        if ((limit != -1 && network.tunnelCount() >= limit) || network.tunnelCount() >= 65535) {
            throw rejected(Reason.QUOTA_REACHED);
        }
        if (network.containsTunnelName(name)) {
            throw rejected(Reason.NAME_CONFLICT);
        }
        UUID tunnelId = Objects.requireNonNull(objectIds().get(), "topology tunnel id");
        UUID initialChannelId = Objects.requireNonNull(objectIds().get(), "topology initial channel id");
        try {
            NetworkSavedData.TunnelCreation created =
                    network.createTunnel(tunnelId, name, initialChannelId, initialChannelName, limit);
            audit(actor, edit, "create_tunnel", created.tunnel().tunnelId());
            release(actor, edit);
            return created;
        } catch (IllegalArgumentException | ArithmeticException failure) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.UNAVAILABLE);
        }
    }

    public NetworkChannelRecord createChannel(ServerPlayer actor, Edit edit, ManagedName name) {
        NetworkSavedData network = requireEdit(actor, edit, Kind.CHANNEL_COLLECTION);
        Objects.requireNonNull(name, "name");
        UUID tunnelId = Objects.requireNonNull(edit.tunnelId(), "tunnelId");
        NetworkTunnelRecord tunnel = network.findTunnel(tunnelId).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        if (tunnel.revision() != edit.revision()) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.STALE_REVISION);
        }
        if (!tunnel.enabled()) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.TUNNEL_DISABLED);
        }
        int limit = settings.channelsPerTunnel();
        if ((limit != -1 && network.channelCount(tunnelId) >= limit) || network.channelCount(tunnelId) >= 65535) {
            throw rejected(Reason.QUOTA_REACHED);
        }
        if (network.containsChannelName(tunnelId, name)) {
            throw rejected(Reason.NAME_CONFLICT);
        }
        UUID id = Objects.requireNonNull(objectIds().get(), "topology object id");
        try {
            NetworkChannelRecord created = network.createChannel(tunnelId, tunnel.revision(), id, name, limit);
            audit(actor, edit, "create_channel", created.channelId());
            release(actor, edit);
            return created;
        } catch (IllegalArgumentException | ArithmeticException failure) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.UNAVAILABLE);
        }
    }

    public NetworkTunnelRecord renameTunnel(ServerPlayer actor, Edit edit, ManagedName name) {
        NetworkSavedData network = requireEdit(actor, edit, Kind.TUNNEL);
        Objects.requireNonNull(name, "name");
        NetworkTunnelRecord current = currentTunnel(network, edit);
        if (!current.name().uniquenessKey().equals(name.uniquenessKey()) && network.containsTunnelName(name)) {
            throw rejected(Reason.NAME_CONFLICT);
        }
        return commitTunnel(
                actor, edit, "rename_tunnel", network.renameTunnel(current.tunnelId(), current.revision(), name));
    }

    public NetworkTunnelRecord setTunnelEnabled(ServerPlayer actor, Edit edit, boolean enabled) {
        NetworkSavedData network = requireEdit(actor, edit, Kind.TUNNEL);
        NetworkTunnelRecord current = currentTunnel(network, edit);
        return commitTunnel(
                actor,
                edit,
                "set_tunnel_enabled",
                network.setTunnelEnabled(current.tunnelId(), current.revision(), enabled));
    }

    public NetworkChannelRecord renameChannel(ServerPlayer actor, Edit edit, ManagedName name) {
        NetworkSavedData network = requireEdit(actor, edit, Kind.CHANNEL);
        Objects.requireNonNull(name, "name");
        NetworkChannelRecord current = currentChannel(network, edit);
        if (!current.name().uniquenessKey().equals(name.uniquenessKey())
                && network.containsChannelName(current.tunnelId(), name)) {
            throw rejected(Reason.NAME_CONFLICT);
        }
        NetworkChannelRecord updated = network.renameChannel(current.channelId(), current.revision(), name)
                .orElseThrow(() -> rejected(Reason.STALE_REVISION));
        if (updated.revision() != edit.revision()) audit(actor, edit, "rename_channel", updated.channelId());
        release(actor, edit);
        return updated;
    }

    public DeletionEdit beginChannelDeletion(ServerPlayer actor, UUID networkId, UUID channelId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkChannelRecord channel = network.findChannel(Objects.requireNonNull(channelId, "channelId"))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        if (network.channelCount(channel.tunnelId()) <= 1) {
            throw rejected(Reason.LAST_CHANNEL);
        }
        Edit edit = acquireChannel(actor, networkId, channelId);
        try {
            return new DeletionEdit(edit, requireNetwork(actor, networkId).summarizeChannelDeletion(channelId));
        } catch (RuntimeException failure) {
            releaseQuietly(actor, edit);
            throw failure;
        }
    }

    public DeletionEdit beginTunnelDeletion(ServerPlayer actor, UUID networkId, UUID tunnelId) {
        Edit edit = acquireTunnel(actor, networkId, tunnelId);
        try {
            return new DeletionEdit(edit, requireNetwork(actor, networkId).summarizeTunnelDeletion(tunnelId));
        } catch (RuntimeException failure) {
            releaseQuietly(actor, edit);
            throw failure;
        }
    }

    public List<NetworkNodeRecord> confirmChannelDeletion(ServerPlayer actor, DeletionEdit deletion) {
        Objects.requireNonNull(deletion, "deletion");
        Edit edit = deletion.edit();
        NetworkSavedData network = requireEdit(actor, edit, Kind.CHANNEL);
        NetworkChannelRecord current = currentChannel(network, edit);
        requireDeletionCurrent(actor, network, deletion);
        requireAffectedNodesUnlocked(deletion);
        requireAffectedNodesAvailable(network, deletion);
        List<NetworkNodeRecord> changed = network.deleteChannel(
                current.channelId(), current.revision(), deletion.impact().topologyRevision());
        publishNodes(edit.networkId(), changed);
        audit(actor, edit, "delete_channel", current.channelId());
        release(actor, edit);
        return changed;
    }

    public List<NetworkNodeRecord> confirmTunnelDeletion(ServerPlayer actor, DeletionEdit deletion) {
        Objects.requireNonNull(deletion, "deletion");
        Edit edit = deletion.edit();
        NetworkSavedData network = requireEdit(actor, edit, Kind.TUNNEL);
        NetworkTunnelRecord current = currentTunnel(network, edit);
        requireDeletionCurrent(actor, network, deletion);
        requireAffectedNodesUnlocked(deletion);
        requireAffectedNodesAvailable(network, deletion);
        List<NetworkNodeRecord> changed = network.deleteTunnel(
                current.tunnelId(), current.revision(), deletion.impact().topologyRevision());
        publishNodes(edit.networkId(), changed);
        audit(actor, edit, "delete_tunnel", current.tunnelId());
        release(actor, edit);
        return changed;
    }

    /**
     * Confirms one server-issued direct-tunnel switch and clears all old node bindings in one authoritative commit.
     * The server thread revalidates sender, role, node/topology revisions, target state and lease; every rejection
     * preserves old bindings and dirty state. Success updates the derived node directory and releases the lease.
     */
    public NetworkNodeRecord confirmTunnelSwitch(ServerPlayer actor, TunnelSwitchEdit switchEdit) {
        Objects.requireNonNull(switchEdit, "switchEdit");
        Edit edit = switchEdit.edit();
        NetworkSavedData network = requireEdit(actor, edit, Kind.NODE);
        NetworkNodeRecord node = currentNode(network, edit);
        if (!node.enabled()) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.NODE_DISABLED);
        }
        if (node.mode() != NodeMode.DIRECT) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.UNAVAILABLE);
        }
        if (network.topologyRevision() != switchEdit.topologyRevision()) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.STALE_REVISION);
        }
        NetworkTunnelRecord target = network.findTunnel(switchEdit.targetTunnelId())
                .orElseThrow(() -> {
                    releaseQuietly(actor, edit);
                    return rejected(Reason.UNAVAILABLE);
                });
        if (!target.enabled()) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.TUNNEL_DISABLED);
        }
        UUID currentTunnel = network.directTunnelId(node.nodeId()).orElseThrow(() -> {
            releaseQuietly(actor, edit);
            return rejected(Reason.STALE_REVISION);
        });
        if (currentTunnel.equals(target.tunnelId())
                || network.directBindings(node.nodeId()).size() != switchEdit.removedBindingCount()) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.STALE_REVISION);
        }
        NetworkSavedData.TunnelSwitchResult result;
        try {
            result = network.switchDirectTunnel(
                    node.nodeId(), node.revision(), target.tunnelId(), switchEdit.topologyRevision());
        } catch (IllegalArgumentException | ArithmeticException | IllegalStateException failure) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.UNAVAILABLE);
        }
        return commitNode(actor, edit, "switch_tunnel", node, result.node());
    }

    public NetworkNodeRecord setDirectBinding(
            ServerPlayer actor, Edit edit, UUID channelId, TransferDirection direction, boolean confirmedReset) {
        StoredResourcePolicy current = inspectResourcePolicy(
                actor, edit.networkId(), Objects.requireNonNull(edit.nodeId(), "nodeId"), channelId);
        return saveDirectBinding(
                actor,
                edit,
                channelId,
                ResourcePolicyEdit.fromStored(current.switchDirection(direction)),
                inspectDirectBinding(actor, edit.networkId(), edit.nodeId(), channelId)
                        .workingFaces(),
                confirmedReset);
    }

    /** Returns a server-owned immutable saved policy or a new input default, without leasing or mutation. */
    public StoredResourcePolicy inspectResourcePolicy(ServerPlayer actor, UUID networkId, UUID nodeId, UUID channelId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        requireNode(networkId, nodeId, network);
        for (DirectNodeBinding binding : network.directBindings(nodeId)) {
            if (binding.channelId().equals(channelId)) return binding.storedPolicy();
        }
        return new StoredResourcePolicy(ResourceTransferPolicy.defaults(TransferDirection.INPUT), java.util.Map.of());
    }

    /** Reads immutable authority without leases, capability discovery or mutation; missing block bindings default empty. */
    public DirectNodeBinding inspectDirectBinding(ServerPlayer actor, UUID networkId, UUID nodeId, UUID channelId) {
        NetworkSavedData network = requireNetwork(actor, networkId);
        NetworkNodeRecord node = requireNode(networkId, nodeId, network);
        if (node.mode() != NodeMode.DIRECT) throw rejected(Reason.UNAVAILABLE);
        NetworkChannelRecord channel = network.findChannel(Objects.requireNonNull(channelId, "channelId"))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        if (network.findTunnel(channel.tunnelId()).isEmpty()) throw rejected(Reason.UNAVAILABLE);
        Optional<UUID> tunnel = network.directTunnelId(nodeId);
        if (tunnel.isPresent() && !tunnel.orElseThrow().equals(channel.tunnelId()))
            throw rejected(Reason.TUNNEL_SWITCH_REQUIRED);
        return network.findDirectBinding(nodeId, channelId)
                .orElseGet(() -> new DirectNodeBinding(
                        nodeId,
                        channelId,
                        ResourceTransferPolicy.defaults(TransferDirection.INPUT),
                        node.form() == io.github.loongin.omniresonance.node.NodeForm.PANEL
                                ? WorkingFaces.attachedFace()
                                : WorkingFaces.explicit(0)));
    }

    /** Compatibility save keeps existing faces and gives missing bindings their legacy fixed attached target. */
    public NetworkNodeRecord setDirectBinding(
            ServerPlayer actor, Edit edit, UUID channelId, ItemTransferPolicy policy, boolean confirmedReset) {
        NetworkSavedData network = requireEdit(actor, edit, Kind.NODE);
        WorkingFaces faces = network.findDirectBinding(Objects.requireNonNull(edit.nodeId(), "nodeId"), channelId)
                .map(DirectNodeBinding::workingFaces)
                .orElse(WorkingFaces.attachedFace());
        return saveDirectBinding(
                actor,
                edit,
                channelId,
                ResourcePolicyEdit.fromStored(
                        new StoredResourcePolicy(ResourceTransferPolicy.legacy(policy), java.util.Map.of())),
                faces,
                confirmedReset);
    }

    /** Revalidates the current lease, revision, node and enabled channel without renewing the lease or mutation. */
    public DirectNodeBinding validateBindingEdit(ServerPlayer actor, Edit edit, UUID channelId) {
        NetworkSavedData network = requireEdit(actor, edit, Kind.NODE);
        NetworkNodeRecord node = currentNode(network, edit);
        requireEnabled(node);
        DirectNodeBinding binding = inspectDirectBinding(actor, edit.networkId(), node.nodeId(), channelId);
        NetworkChannelRecord channel = network.findChannel(channelId).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        if (!network.findTunnel(channel.tunnelId())
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE))
                .enabled()) throw rejected(Reason.TUNNEL_DISABLED);
        return binding;
    }

    /** Validates a decoded detached intent against current authority without saving or renewing the edit lease. */
    public void validatePolicyIntent(ServerPlayer actor, Edit edit, UUID channelId, ResourcePolicyEdit intent) {
        DirectNodeBinding binding = validateBindingEdit(actor, edit, channelId);
        intent.reconcile(binding.storedPolicy(), repository().registeredResourceTypes());
    }

    /** Server-thread atomic player save; revalidates lease, revision, scope and form before changing owned authority. */
    public NetworkNodeRecord saveDirectBinding(
            ServerPlayer actor,
            Edit edit,
            UUID channelId,
            ResourcePolicyEdit intent,
            WorkingFaces faces,
            boolean confirmedReset) {
        NetworkSavedData network = requireEdit(actor, edit, Kind.NODE);
        NetworkNodeRecord node = currentNode(network, edit);
        Objects.requireNonNull(intent, "intent");
        if (intent.discardPreviousDirectionFields() && !confirmedReset) throw rejected(Reason.RESET_REQUIRED);
        StoredResourcePolicy current = network.findDirectBinding(node.nodeId(), channelId)
                .map(DirectNodeBinding::storedPolicy)
                .orElseGet(() -> new StoredResourcePolicy(
                        ResourceTransferPolicy.defaults(TransferDirection.INPUT), java.util.Map.of()));
        StoredResourcePolicy stored;
        try {
            stored = intent.reconcile(current, repository().registeredResourceTypes());
        } catch (IllegalArgumentException invalidIntent) {
            throw rejected(Reason.UNAVAILABLE);
        }
        ResourceTransferPolicy policy = stored.effectivePolicy();
        try {
            Objects.requireNonNull(faces, "faces").validate(node.form());
        } catch (IllegalArgumentException invalidFaces) {
            throw rejected(Reason.UNAVAILABLE);
        }
        requireEnabled(node);
        if (node.mode() != NodeMode.DIRECT) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.UNAVAILABLE);
        }
        NetworkChannelRecord channel = network.findChannel(Objects.requireNonNull(channelId, "channelId"))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        NetworkTunnelRecord tunnel =
                network.findTunnel(channel.tunnelId()).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        if (!tunnel.enabled()) {
            throw rejected(Reason.TUNNEL_DISABLED);
        }
        Optional<UUID> currentTunnel = network.directTunnelId(node.nodeId());
        if (currentTunnel.isPresent() && !currentTunnel.orElseThrow().equals(tunnel.tunnelId())) {
            throw rejected(Reason.TUNNEL_SWITCH_REQUIRED);
        }
        Optional<DirectNodeBinding> existing = network.directBindings(node.nodeId()).stream()
                .filter(binding -> binding.channelId().equals(channelId))
                .findFirst();
        if (existing.isPresent() && existing.orElseThrow().direction() != policy.direction() && !confirmedReset) {
            throw rejected(Reason.RESET_REQUIRED);
        }
        UUID selected = policy.filterPresetId();
        UUID previous =
                existing.map(binding -> binding.policy().filterPresetId()).orElse(null);
        if (selected != null
                && !selected.equals(previous)
                && repository()
                        .findOwner(network.metadata().ownerId())
                        .flatMap(owner -> owner.findPreset(selected))
                        .isEmpty()) {
            throw rejected(Reason.UNAVAILABLE);
        }
        try {
            NetworkNodeRecord updated = network.setDirectBinding(
                    node.nodeId(),
                    node.revision(),
                    channelId,
                    stored,
                    faces,
                    confirmedReset,
                    settings.channelBindingsPerDirectNode());
            return commitNode(actor, edit, "configure_channel", node, updated);
        } catch (IllegalStateException invalidAuthority) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.UNAVAILABLE);
        } catch (IllegalArgumentException quota) {
            throw rejected(Reason.QUOTA_REACHED);
        }
    }

    public NetworkNodeRecord removeDirectBinding(ServerPlayer actor, Edit edit, UUID channelId) {
        NetworkSavedData network = requireEdit(actor, edit, Kind.NODE);
        NetworkNodeRecord node = currentNode(network, edit);
        requireEnabled(node);
        if (node.mode() != NodeMode.DIRECT) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.UNAVAILABLE);
        }
        NetworkChannelRecord channel = network.findChannel(Objects.requireNonNull(channelId, "channelId"))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        NetworkTunnelRecord tunnel =
                network.findTunnel(channel.tunnelId()).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        if (!tunnel.enabled()) {
            throw rejected(Reason.TUNNEL_DISABLED);
        }
        NetworkNodeRecord updated = network.removeDirectBinding(node.nodeId(), node.revision(), channelId)
                .orElseThrow(() -> rejected(Reason.STALE_REVISION));
        return commitNode(actor, edit, "remove_channel_configuration", node, updated);
    }

    /** Returns the current complete domain seed under the same authoritative lease, without publication or mutation. */
    public DomainNodeConfiguration validateDomainEdit(ServerPlayer actor, Edit edit) {
        NetworkSavedData network = requireEdit(actor, edit, Kind.NODE);
        NetworkNodeRecord node = currentNode(network, edit);
        requireEnabled(node);
        if (node.mode() != NodeMode.DOMAIN) throw rejected(Reason.UNAVAILABLE);
        return network.domainConfiguration(node.nodeId())
                .orElseGet(() -> new DomainNodeConfiguration(
                        node.nodeId(),
                        new StoredResourcePolicy(
                                ResourceTransferPolicy.defaults(TransferDirection.INPUT), java.util.Map.of()),
                        node.form() == io.github.loongin.omniresonance.node.NodeForm.PANEL
                                ? WorkingFaces.attachedFace()
                                : WorkingFaces.explicit(0),
                        false));
    }

    /** Validates a detached domain edit intent against its current trusted missing-type seed, without mutation. */
    public void validateDomainPolicyIntent(ServerPlayer actor, Edit edit, ResourcePolicyEdit intent) {
        intent.reconcile(
                validateDomainEdit(actor, edit).storedPolicy(), repository().registeredResourceTypes());
    }

    /** Server-thread full-form save; only this intent clears a legacy domain's pending state after validation. */
    public NetworkNodeRecord saveDomainConfiguration(
            ServerPlayer actor, Edit edit, ResourcePolicyEdit intent, WorkingFaces faces, boolean confirmedReset) {
        NetworkSavedData network = requireEdit(actor, edit, Kind.NODE);
        NetworkNodeRecord node = currentNode(network, edit);
        DomainNodeConfiguration current = validateDomainEdit(actor, edit);
        Objects.requireNonNull(intent, "intent");
        if (intent.discardPreviousDirectionFields() && !confirmedReset) throw rejected(Reason.RESET_REQUIRED);
        StoredResourcePolicy stored;
        try {
            stored = intent.reconcile(current.storedPolicy(), repository().registeredResourceTypes());
            Objects.requireNonNull(faces, "faces").validate(node.form());
        } catch (IllegalArgumentException invalidIntent) {
            throw rejected(Reason.UNAVAILABLE);
        }
        ResourceTransferPolicy policy = stored.effectivePolicy();
        if (network.domainConfiguration(node.nodeId()).isPresent()
                && current.direction() != policy.direction()
                && !confirmedReset) throw rejected(Reason.RESET_REQUIRED);
        UUID selected = policy.filterPresetId();
        if (selected != null
                && !selected.equals(current.policy().filterPresetId())
                && repository()
                        .findOwner(network.metadata().ownerId())
                        .flatMap(owner -> owner.findPreset(selected))
                        .isEmpty()) throw rejected(Reason.UNAVAILABLE);
        try {
            NetworkNodeRecord updated =
                    network.saveDomainConfiguration(node.nodeId(), node.revision(), stored, faces, confirmedReset);
            return commitNode(actor, edit, "configure_domain", node, updated);
        } catch (IllegalStateException invalidAuthority) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.UNAVAILABLE);
        } catch (IllegalArgumentException invalidPolicy) {
            throw rejected(Reason.UNAVAILABLE);
        }
    }

    public NetworkNodeRecord setDomainConfiguration(
            ServerPlayer actor, Edit edit, TransferDirection direction, boolean confirmedReset) {
        NetworkSavedData network = requireEdit(actor, edit, Kind.NODE);
        NetworkNodeRecord node = currentNode(network, edit);
        requireEnabled(node);
        if (node.mode() != NodeMode.DOMAIN) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.UNAVAILABLE);
        }
        Optional<DomainNodeConfiguration> existing = network.domainConfiguration(node.nodeId());
        if (existing.isPresent() && existing.orElseThrow().direction() != direction && !confirmedReset) {
            throw rejected(Reason.RESET_REQUIRED);
        }
        try {
            NetworkNodeRecord updated = network.setDomainConfiguration(
                    node.nodeId(), node.revision(), Objects.requireNonNull(direction, "direction"), confirmedReset);
            return commitNode(actor, edit, "configure_domain", node, updated);
        } catch (IllegalStateException invalidAuthority) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.UNAVAILABLE);
        }
    }

    public NetworkNodeRecord removeDomainConfiguration(ServerPlayer actor, Edit edit) {
        NetworkSavedData network = requireEdit(actor, edit, Kind.NODE);
        NetworkNodeRecord node = currentNode(network, edit);
        requireEnabled(node);
        if (node.mode() != NodeMode.DOMAIN) {
            releaseQuietly(actor, edit);
            throw rejected(Reason.UNAVAILABLE);
        }
        NetworkNodeRecord updated = network.removeDomainConfiguration(node.nodeId(), node.revision())
                .orElseThrow(() -> rejected(Reason.STALE_REVISION));
        return commitNode(actor, edit, "remove_domain_configuration", node, updated);
    }

    public void heartbeat(ServerPlayer actor, Edit edit) {
        requireActor(actor);
        Objects.requireNonNull(edit, "edit");
        try {
            requireNetwork(actor, edit.networkId());
        } catch (Rejected rejected) {
            releaseQuietly(actor, edit);
            throw rejected;
        }
        if (!locks().renew(edit.token(), actor.getUUID(), currentTick)) {
            throw rejected(Reason.LOCK_EXPIRED);
        }
    }

    public void cancel(ServerPlayer actor, Edit edit) {
        requireActor(actor);
        Objects.requireNonNull(edit, "edit");
        if (!locks().release(edit.token(), actor.getUUID())) {
            throw rejected(Reason.LOCK_EXPIRED);
        }
    }

    /** Applies only a newer validated settings snapshot from the same loaded world epoch. */
    public void applyConfiguration(ServerConfig.State state) {
        requireServerThread();
        Objects.requireNonNull(state, "state");
        if (configurationLoaded
                && state.loaded()
                && state.epoch() == configurationEpoch
                && state.revision() > configurationRevision
                && state.settings() != null) {
            settings = state.settings();
            configurationRevision = state.revision();
        }
    }

    /** Advances the service's monotonic lease clock; the shared lifecycle owner removes expired leases. */
    public void tick() {
        requireServerThread();
        currentTick = Math.incrementExact(currentTick);
    }

    /** Releases service references only; the shared node-management owner clears the common lock table. */
    @Override
    public void close() {
        MinecraftServer activeServer = server;
        if (activeServer == null) {
            return;
        }
        if (!activeServer.isSameThread()) {
            throw new IllegalStateException("Topology management closed outside the server thread");
        }
        server = null;
        networks = null;
        repository = null;
        nodes = null;
        locks = null;
        objectIds = null;
    }

    private Edit acquire(
            ServerPlayer actor,
            Kind kind,
            UUID networkId,
            @Nullable UUID tunnelId,
            @Nullable UUID channelId,
            @Nullable UUID nodeId,
            long revision,
            long topologyRevision,
            UUID lockId) {
        EditLockTable.Token token =
                locks().tryAcquire(lockId, actor.getUUID(), currentTick).orElseThrow(() -> rejected(Reason.LOCKED));
        return new Edit(token, kind, networkId, tunnelId, channelId, nodeId, revision, topologyRevision);
    }

    private NetworkSavedData requireEdit(ServerPlayer actor, Edit edit, Kind kind) {
        requireActor(actor);
        Objects.requireNonNull(edit, "edit");
        if (edit.kind() != kind || !locks().isHeld(edit.token(), actor.getUUID(), currentTick)) {
            throw rejected(Reason.LOCK_EXPIRED);
        }
        try {
            return requireNetwork(actor, edit.networkId());
        } catch (Rejected rejected) {
            if (rejected.reason() == Reason.NO_ACCESS || rejected.reason() == Reason.UNAVAILABLE) {
                releaseQuietly(actor, edit);
            }
            throw rejected;
        }
    }

    private NetworkTunnelRecord currentTunnel(NetworkSavedData network, Edit edit) {
        NetworkTunnelRecord current = network.findTunnel(Objects.requireNonNull(edit.tunnelId(), "tunnelId"))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        if (current.revision() != edit.revision()) {
            releaseQuietly(edit.token().playerId(), edit);
            throw rejected(Reason.STALE_REVISION);
        }
        return current;
    }

    private NetworkChannelRecord currentChannel(NetworkSavedData network, Edit edit) {
        NetworkChannelRecord current = network.findChannel(Objects.requireNonNull(edit.channelId(), "channelId"))
                .orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        if (current.revision() != edit.revision()) {
            releaseQuietly(edit.token().playerId(), edit);
            throw rejected(Reason.STALE_REVISION);
        }
        return current;
    }

    private NetworkNodeRecord currentNode(NetworkSavedData network, Edit edit) {
        NetworkNodeRecord current =
                requireNode(edit.networkId(), Objects.requireNonNull(edit.nodeId(), "nodeId"), network);
        if (current.revision() != edit.revision()) {
            releaseQuietly(edit.token().playerId(), edit);
            throw rejected(Reason.STALE_REVISION);
        }
        return current;
    }

    private NetworkNodeRecord requireNode(UUID networkId, UUID nodeId, NetworkSavedData network) {
        NetworkNodeDirectory.Lookup lookup = nodes().byId(nodeId);
        if (lookup.status() != NetworkNodeDirectory.Status.UNIQUE) {
            throw rejected(Reason.UNAVAILABLE);
        }
        NetworkNodeDirectory.Entry entry = lookup.entry().orElseThrow();
        NetworkNodeRecord authoritative = network.findNode(nodeId).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        if (!entry.networkId().equals(networkId) || !entry.record().equals(authoritative)) {
            throw rejected(Reason.UNAVAILABLE);
        }
        return authoritative;
    }

    private NetworkSavedData requireNetwork(ServerPlayer actor, UUID networkId) {
        requireActor(actor);
        Objects.requireNonNull(networkId, "networkId");
        NetworkMetadata indexed = networks().find(networkId).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        NetworkSavedData network =
                repository().findLoadedNetwork(networkId).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        NetworkMetadata authoritative = network.metadata();
        if (!authoritative.equals(indexed)) {
            throw rejected(Reason.UNAVAILABLE);
        }
        if (!NetworkPermissions.canManage(actor.getUUID(), authoritative.ownerId(), authoritative.administrators())) {
            throw rejected(Reason.NO_ACCESS);
        }
        return network;
    }

    private void requireDeletionCurrent(ServerPlayer actor, NetworkSavedData network, DeletionEdit deletion) {
        if (network.topologyRevision() != deletion.impact().topologyRevision()) {
            releaseQuietly(actor, deletion.edit());
            throw rejected(Reason.STALE_REVISION);
        }
    }

    private void requireAffectedNodesAvailable(NetworkSavedData network, DeletionEdit deletion) {
        for (UUID nodeId : deletion.impact().affectedNodeIds()) {
            requireNode(deletion.edit().networkId(), nodeId, network);
        }
    }

    private void requireAffectedNodesUnlocked(DeletionEdit deletion) {
        if (locks().hasConflictingLocks(
                        deletion.impact().affectedNodeIds(), deletion.edit().token(), currentTick)) {
            throw rejected(Reason.LOCKED);
        }
    }

    private void audit(ServerPlayer actor, Edit edit, String action, UUID target) {
        repository()
                .auditNetwork(
                        edit.networkId(),
                        io.github.loongin.omniresonance.persistence.AuditEntry.of(action, actor, target, ""));
    }

    private NetworkTunnelRecord commitTunnel(
            ServerPlayer actor, Edit edit, String action, Optional<NetworkTunnelRecord> result) {
        NetworkTunnelRecord updated = result.orElseThrow(() -> rejected(Reason.STALE_REVISION));
        if (updated.revision() != edit.revision()) audit(actor, edit, action, updated.tunnelId());
        release(actor, edit);
        return updated;
    }

    private NetworkNodeRecord commitNode(
            ServerPlayer actor, Edit edit, String action, NetworkNodeRecord current, NetworkNodeRecord updated) {
        if (updated != current) {
            nodes().update(
                            new NetworkNodeDirectory.Entry(edit.networkId(), current),
                            new NetworkNodeDirectory.Entry(edit.networkId(), updated));
            audit(actor, edit, action, updated.nodeId());
        }
        release(actor, edit);
        return updated;
    }

    private void publishNodes(UUID networkId, List<NetworkNodeRecord> changed) {
        for (NetworkNodeRecord updated : changed) {
            NetworkNodeDirectory.Entry current =
                    nodes().byId(updated.nodeId()).entry().orElseThrow();
            nodes().update(current, new NetworkNodeDirectory.Entry(networkId, updated));
        }
    }

    private static void requireEnabled(NetworkNodeRecord node) {
        if (!node.enabled()) {
            throw rejected(Reason.NODE_DISABLED);
        }
    }

    private void release(ServerPlayer actor, Edit edit) {
        if (!locks().release(edit.token(), actor.getUUID())) {
            throw rejected(Reason.LOCK_EXPIRED);
        }
    }

    private void releaseQuietly(ServerPlayer actor, Edit edit) {
        locks().release(edit.token(), actor.getUUID());
    }

    private void releaseQuietly(UUID actorId, Edit edit) {
        locks().release(edit.token(), actorId);
    }

    private void requireActor(ServerPlayer actor) {
        Objects.requireNonNull(actor, "actor");
        requireServerThread();
        if (actor.server != server) {
            throw rejected(Reason.UNAVAILABLE);
        }
    }

    private void requireServerThread() {
        MinecraftServer activeServer = server;
        if (activeServer == null) {
            throw rejected(Reason.UNAVAILABLE);
        }
        if (!activeServer.isSameThread()) {
            throw new IllegalStateException("Topology management accessed outside the server thread");
        }
    }

    private NetworkDirectory networks() {
        requireServerThread();
        return Objects.requireNonNull(networks, "Topology management is closed");
    }

    public io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory resourceAdapters() {
        return repository().resourceAdapters();
    }

    private SavedNetworkRepository repository() {
        requireServerThread();
        return Objects.requireNonNull(repository, "Topology management is closed");
    }

    private NetworkNodeDirectory nodes() {
        requireServerThread();
        return Objects.requireNonNull(nodes, "Topology management is closed");
    }

    private EditLockTable locks() {
        requireServerThread();
        return Objects.requireNonNull(locks, "Topology management is closed");
    }

    private Supplier<UUID> objectIds() {
        requireServerThread();
        return Objects.requireNonNull(objectIds, "Topology management is closed");
    }

    private static Rejected rejected(Reason reason) {
        return new Rejected(reason);
    }
}
