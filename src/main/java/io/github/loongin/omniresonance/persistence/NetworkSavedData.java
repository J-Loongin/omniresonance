// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.network.DirectNodeBinding;
import io.github.loongin.omniresonance.network.DomainNodeConfiguration;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkChannelRecord;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.network.NetworkTopologyIndex;
import io.github.loongin.omniresonance.network.NetworkTunnelRecord;
import io.github.loongin.omniresonance.network.TopologyDeletionImpact;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread-owned v9 network shard containing immutable metadata, node records and normalized topology.
 *
 * <p>Create/load and every accessor or mutation run on the constructing server thread. Tags and caller collections
 * are never retained. Mutations validate their full intent before changing indexes and mark dirty only on a real
 * change. No method simulates, performs disk I/O, accesses a world, authorizes a player or exposes mutable state.
 * Known v3/v4/v5/v6/v7/v8 schemas migrate in memory; malformed or unsupported input is rejected, never repaired or replaced.
 */
public final class NetworkSavedData extends SavedData {
    /** Owner-bound, nonmutating rename preflight; immutable snapshots may be inspected on the server thread. */
    public static final class PreparedRename {
        private final NetworkSavedData owner;
        private final NetworkMetadata previous;
        private final NetworkMetadata next;
        private final long previousRevision;
        private final long nextRevision;

        private PreparedRename(
                NetworkSavedData owner,
                NetworkMetadata previous,
                NetworkMetadata next,
                long previousRevision,
                long nextRevision) {
            this.owner = owner;
            this.previous = previous;
            this.next = next;
            this.previousRevision = previousRevision;
            this.nextRevision = nextRevision;
        }

        /** Returns the immutable pre-commit metadata without changing authority or persistent state. */
        public NetworkMetadata previous() {
            return previous;
        }

        /** Returns the immutable proposed metadata without committing or dirtying the shard. */
        public NetworkMetadata next() {
            return next;
        }
    }

    /** Owner-bound, nonmutating membership preflight; immutable snapshots may be inspected on the server thread. */
    public static final class PreparedAdministratorChange {
        private final NetworkSavedData owner;
        private final NetworkMetadata previous;
        private final NetworkMetadata next;
        private final UUID playerId;
        private final boolean add;
        private final long previousRevision;
        private final long nextRevision;

        private PreparedAdministratorChange(
                NetworkSavedData owner,
                NetworkMetadata previous,
                NetworkMetadata next,
                UUID playerId,
                boolean add,
                long previousRevision,
                long nextRevision) {
            this.owner = owner;
            this.previous = previous;
            this.next = next;
            this.playerId = playerId;
            this.add = add;
            this.previousRevision = previousRevision;
            this.nextRevision = nextRevision;
        }

        /** Returns the immutable pre-commit metadata without authority changes or I/O. */
        public NetworkMetadata previous() {
            return previous;
        }

        /** Returns the immutable proposed metadata without authorizing or committing the change. */
        public NetworkMetadata next() {
            return next;
        }
    }

    /** Immutable result of one atomic tunnel and mandatory initial-channel creation. */
    public record TunnelCreation(NetworkTunnelRecord tunnel, NetworkChannelRecord initialChannel) {
        public TunnelCreation {
            Objects.requireNonNull(tunnel, "tunnel");
            Objects.requireNonNull(initialChannel, "initialChannel");
            if (!initialChannel.tunnelId().equals(tunnel.tunnelId()) || initialChannel.channelNumber() != 1) {
                throw new IllegalArgumentException("Initial channel does not belong to its created tunnel");
            }
        }
    }

    /** Immutable result of clearing one direct node before navigating to another tunnel. */
    public record TunnelSwitchResult(NetworkNodeRecord node, UUID targetTunnelId, int removedBindingCount) {
        public TunnelSwitchResult {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(targetTunnelId, "targetTunnelId");
            if (removedBindingCount < 1) {
                throw new IllegalArgumentException("Tunnel switch must remove at least one binding");
            }
        }
    }

    /** Opaque prevalidated source removal used only by the trusted cross-network authority commit. */
    public static final class PreparedNodeMoveOut {
        private final NetworkSavedData owner;
        private final NetworkNodeRecord source;
        private final long nextTopologyRevision;

        private PreparedNodeMoveOut(NetworkSavedData owner, NetworkNodeRecord source, long nextTopologyRevision) {
            this.owner = owner;
            this.source = source;
            this.nextTopologyRevision = nextTopologyRevision;
        }

        public NetworkNodeRecord source() {
            return source;
        }
    }

    /** Opaque prevalidated target insertion exposing only the immutable moved record. */
    public static final class PreparedNodeMoveIn {
        private final NetworkSavedData owner;
        private final NetworkNodeRecord moved;
        private final long nextNodeNumber;
        private final long nextTopologyRevision;

        private PreparedNodeMoveIn(
                NetworkSavedData owner, NetworkNodeRecord moved, long nextNodeNumber, long nextTopologyRevision) {
            this.owner = owner;
            this.moved = moved;
            this.nextNodeNumber = nextNodeNumber;
            this.nextTopologyRevision = nextTopologyRevision;
        }

        public NetworkNodeRecord moved() {
            return moved;
        }
    }

    /** Returns a detached bounded snapshot on the owning server thread; no state changes or I/O occur. */
    public java.util.List<AuditEntry> auditEntries() {
        requireOwningThread();
        return audit.snapshot();
    }

    /** Appends server-authored metadata on the owning thread after a confirmed action; zero retains history.
     * Invalid input rejects before mutation. Marks only this shard dirty; performs no simulation or I/O.
     */
    public void appendAudit(AuditEntry entry, int capacity) {
        requireOwningThread();
        if (audit.append(entry, capacity)) super.setDirty(true);
    }

    private final AuditRing audit = new AuditRing();
    private final Thread owningThread = Thread.currentThread();
    private NetworkMetadata metadata;
    private final NavigableSet<UUID> administratorIds = new TreeSet<>();
    private final Map<UUID, NetworkNodeRecord> nodes = new HashMap<>();
    private final Map<String, UUID> nodesByName = new HashMap<>();
    private final Map<GlobalPos, UUID> nodesByPosition = new HashMap<>();
    private final NetworkTopologyIndex topology;
    private long lastNodeNumber;
    private long lastTunnelNumber;
    private long topologyRevision;
    private long managementRevision;
    private long bucketCreatedMask;
    private final io.github.loongin.omniresonance.recovery.RecoveryBuffer recovery =
            new io.github.loongin.omniresonance.recovery.RecoveryBuffer(this::recoveryChanged);

    private NetworkSavedData(
            NetworkMetadata metadata,
            long lastNodeNumber,
            Map<UUID, NetworkNodeRecord> initialNodes,
            NetworkTopologyNbt.Decoded topology) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        administratorIds.addAll(metadata.administrators());
        this.lastNodeNumber = lastNodeNumber;
        for (NetworkNodeRecord record : initialNodes.values()) {
            nodes.put(record.nodeId(), record);
            nodesByName.put(record.name().uniquenessKey(), record.nodeId());
            nodesByPosition.put(record.position(), record.nodeId());
        }
        lastTunnelNumber = topology.lastTunnelNumber();
        topologyRevision = topology.topologyRevision();
        this.topology = new NetworkTopologyIndex(
                topology.tunnels().values(),
                topology.channels().values(),
                topology.directBindings(),
                topology.domainConfigurations().values());
    }

    /** Creates an empty dirty v9 shard without simulation, I/O, world access or caller mutation. */
    public static NetworkSavedData create(NetworkMetadata metadata) {
        NetworkSavedData data = new NetworkSavedData(metadata, 0, Map.of(), NetworkTopologyNbt.empty());
        data.setDirty();
        return data;
    }

    /**
     * Strictly decodes a clean v3/v4/v5/v6/v7/v8/v9 shard; v3 gains empty topology, v3/v4 gain management revision zero, and legacy direct bindings gain default policies and empty recovery.
     * Migration is in memory and does not retain, dirty or modify the caller's tag or files.
     * Missing inputs throw {@link NullPointerException}; malformed/unsupported data throws {@link IllegalArgumentException}.
     */
    public static NetworkSavedData load(UUID expectedId, CompoundTag tag) {
        return load(
                expectedId,
                tag,
                Set.copyOf(io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory.nativeDefaults()
                        .types()));
    }

    public static NetworkSavedData load(
            UUID expectedId, CompoundTag tag, Set<net.minecraft.resources.ResourceLocation> registeredTypes) {
        int schemaVersion = ManagedDataNbt.readSchemaVersion(tag);
        Set<String> fields =
                switch (schemaVersion) {
                    case 3 -> ManagedDataNbt.NETWORK_V3_FIELDS;
                    case 4 -> ManagedDataNbt.NETWORK_V4_FIELDS;
                    case 5 -> ManagedDataNbt.NETWORK_V5_FIELDS;
                    case 6, 7, 8 -> ManagedDataNbt.NETWORK_V8_FIELDS;
                    case 9 -> ManagedDataNbt.NETWORK_V9_FIELDS;
                    case 10, ManagedDataNbt.NETWORK_SCHEMA_VERSION -> ManagedDataNbt.NETWORK_FIELDS;
                    default -> throw new IllegalArgumentException("Unsupported network schema");
                };
        ManagedDataNbt.validateSchemaAndFields(tag, schemaVersion, fields);
        UUID id = ManagedDataNbt.readIdentity(tag, "network_id", expectedId);
        NetworkMetadata metadata = new NetworkMetadata(
                id,
                ManagedDataNbt.readUuid(tag, "owner_id"),
                ManagedDataNbt.readName(tag),
                ManagedDataNbt.readCreationOrder(tag),
                ManagedDataNbt.readAdministrators(tag));
        NetworkNodeNbt.Decoded decoded = NetworkNodeNbt.decode(tag);
        NetworkTopologyNbt.Decoded topology = schemaVersion == 3
                ? NetworkTopologyNbt.empty()
                : NetworkTopologyNbt.decode(tag, decoded.nodes(), registeredTypes);
        long managementRevision = schemaVersion < 5 ? 0 : ManagedDataNbt.readManagementRevision(tag);
        NetworkSavedData data = new NetworkSavedData(metadata, decoded.lastNodeNumber(), decoded.nodes(), topology);
        data.managementRevision = managementRevision;
        if (schemaVersion >= 9) {
            ManagedDataNbt.requireType(tag, "bucket_created_mask", net.minecraft.nbt.Tag.TAG_LONG);
            data.bucketCreatedMask = tag.getLong("bucket_created_mask");
        }
        if (schemaVersion >= 6) data.recovery.restore(RecoveryNbt.decode(tag));
        if (schemaVersion >= 10) {
            ManagedDataNbt.requireType(tag, "audit_entries", net.minecraft.nbt.Tag.TAG_LIST);
            data.audit.restore(AuditNbt.decode((net.minecraft.nbt.ListTag) tag.get("audit_entries")));
        }
        return data;
    }

    /** Owner-thread immutable bitmap query; all 64 bits are identities, not a signed quantity. */
    public long bucketCreatedMask() {
        requireOwningThread();
        return bucketCreatedMask;
    }

    /** Adds known bucket identities on the owner thread; clearing historical bits rejects without mutation. */
    public void markStorageBuckets(long mask) {
        requireOwningThread();
        if ((mask & bucketCreatedMask) != bucketCreatedMask)
            throw new IllegalArgumentException("Cannot clear created storage buckets");
        if (mask == bucketCreatedMask) return;
        bucketCreatedMask = mask;
        super.setDirty(true);
    }

    /** Returns this shard's owned server-thread buffer; positive committed remainder marks this shard dirty. */
    public io.github.loongin.omniresonance.recovery.RecoveryBuffer recovery() {
        requireOwningThread();
        return recovery;
    }

    /** Returns immutable metadata without mutation or simulation; wrong-thread access is rejected. */
    public NetworkMetadata metadata() {
        requireOwningThread();
        return metadata;
    }

    /** Reads metadata's revision on the owning thread without simulation, authority changes or I/O. */
    public long managementRevision() {
        requireOwningThread();
        return managementRevision;
    }

    /** Reads the current member count in O(1) on the owning thread without copying or changing authority. */
    public int administratorCount() {
        requireOwningThread();
        return administratorIds.size();
    }

    /**
     * Returns an independently owned, UUID-ordered member page on the server thread in O(log A + limit).
     * The derived tree is owned by this shard, rebuilt at load and updated only on membership commits. Invalid
     * anchors or limits reject before reading a page; no simulation, authorization, world access or I/O occurs.
     */
    public List<UUID> pageAdministratorIds(@Nullable UUID anchor, boolean backwards, int limit) {
        requireOwningThread();
        if (limit < 1
                || limit > 128
                || (backwards && anchor == null)
                || (anchor != null && !administratorIds.contains(anchor))) {
            throw new IllegalArgumentException("Invalid administrator page bounds");
        }
        NavigableSet<UUID> window = anchor == null
                ? administratorIds
                : backwards
                        ? administratorIds.headSet(anchor, false).descendingSet()
                        : administratorIds.tailSet(anchor, false);
        List<UUID> result = new ArrayList<>(Math.min(limit, administratorIds.size()));
        Iterator<UUID> iterator = window.iterator();
        while (result.size() < limit && iterator.hasNext()) {
            result.add(iterator.next());
        }
        if (backwards) {
            Collections.reverse(result);
        }
        return List.copyOf(result);
    }

    /**
     * Preflights one member change on the owning server thread; the caller must separately authorize the actor.
     * No authority, dirty state, cursor or cache is changed. Stale revision, owner target, duplicate/no-op and
     * exceeded quota reject before allocation/commit; overflow is explicit. Cost is O(A) for the new immutable set.
     */
    public PreparedAdministratorChange prepareAdministratorChange(
            UUID playerId, boolean add, long expectedRevision, int limit) {
        requireOwningThread();
        Objects.requireNonNull(playerId, "playerId");
        validateQuota(limit, 0, 1024, "administrator");
        if (expectedRevision < 0
                || expectedRevision != managementRevision
                || metadata.ownerId().equals(playerId)
                || administratorIds.contains(playerId) == add) {
            throw new IllegalArgumentException("Invalid or stale administrator change");
        }
        if (add
                && (administratorIds.size() >= NetworkMetadata.MAXIMUM_ADMINISTRATORS
                        || (limit != -1 && administratorIds.size() >= limit))) {
            throw new IllegalArgumentException("Administrator quota reached");
        }
        long nextRevision = Math.incrementExact(managementRevision);
        Set<UUID> nextMembers = new HashSet<>(metadata.administrators());
        if (add) {
            nextMembers.add(playerId);
        } else {
            nextMembers.remove(playerId);
        }
        NetworkMetadata next = new NetworkMetadata(
                metadata.id(), metadata.ownerId(), metadata.name(), metadata.creationOrder(), nextMembers);
        return new PreparedAdministratorChange(this, metadata, next, playerId, add, managementRevision, nextRevision);
    }

    /**
     * Commits one exact owner-bound preflight on the same server thread after directory validation. Wrong-shard
     * and stale/reused values fail before modification. Real changes update only membership, its revision and
     * dirty state; node/topology records and caller snapshots stay unchanged. No simulation or I/O occurs.
     */
    public NetworkMetadata commitAdministratorChange(PreparedAdministratorChange prepared) {
        requireOwningThread();
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.owner != this
                || prepared.previous != metadata
                || prepared.previousRevision != managementRevision) {
            throw new IllegalArgumentException("Stale or foreign administrator change");
        }
        if (prepared.add) {
            administratorIds.add(prepared.playerId);
        } else {
            administratorIds.remove(prepared.playerId);
        }
        metadata = prepared.next;
        managementRevision = prepared.nextRevision;
        setDirty();
        return metadata;
    }

    /**
     * Preflights one real network rename on the owning server thread without changing revisions or dirty state.
     * The caller must separately authorize the actor and validate owner-scoped uniqueness in the directory.
     * Missing, unchanged, or stale input rejects before allocation; revision overflow is explicit.
     */
    public PreparedRename prepareRename(ManagedName name, long expectedRevision) {
        requireOwningThread();
        Objects.requireNonNull(name, "name");
        if (expectedRevision < 0
                || expectedRevision != managementRevision
                || metadata.name().equals(name)) {
            throw new IllegalArgumentException("Invalid or stale network rename");
        }
        long nextRevision = Math.incrementExact(managementRevision);
        NetworkMetadata next = new NetworkMetadata(
                metadata.id(), metadata.ownerId(), name, metadata.creationOrder(), metadata.administrators());
        return new PreparedRename(this, metadata, next, managementRevision, nextRevision);
    }

    /**
     * Commits one exact owner-bound rename after directory preflight. Wrong-shard, stale and reused values reject
     * before mutation. Success changes only metadata name, management revision and dirty state; no I/O occurs.
     */
    public NetworkMetadata commitRename(PreparedRename prepared) {
        requireOwningThread();
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.owner != this
                || prepared.previous != metadata
                || prepared.previousRevision != managementRevision) {
            throw new IllegalArgumentException("Stale or foreign network rename");
        }
        metadata = prepared.next;
        managementRevision = prepared.nextRevision;
        setDirty();
        return metadata;
    }

    /** Returns a stable immutable node snapshot without world access or mutation. */
    /** Event-driven chunk-request snapshot; unsorted and never used as a per-tick world scan. */
    public List<NetworkNodeRecord> chunkLoadingRequests() {
        requireOwningThread();
        var result = new java.util.ArrayList<NetworkNodeRecord>();
        for (var node : nodes.values()) if (node.chunkLoadingRequested()) result.add(node);
        return List.copyOf(result);
    }

    public List<NetworkNodeRecord> nodes() {
        requireOwningThread();
        return NetworkNodeNbt.sorted(nodes.values());
    }

    /** Returns the authoritative node-record count in O(1) without copying or world access. */
    public int nodeCount() {
        requireOwningThread();
        return nodes.size();
    }

    /** Returns the last allocated positive node number without mutation. */
    public long lastNodeNumber() {
        requireOwningThread();
        return lastNodeNumber;
    }

    /** Returns the current nonnegative network topology revision without mutation. */
    public long topologyRevision() {
        requireOwningThread();
        return topologyRevision;
    }

    /** Returns the last allocated tunnel number without changing numbering state. */
    public long lastTunnelNumber() {
        requireOwningThread();
        return lastTunnelNumber;
    }

    /** Returns the current tunnel count in O(1) without copying records. */
    public int tunnelCount() {
        requireOwningThread();
        return topology.tunnelCount();
    }

    /** Returns one tunnel's channel count in O(1) without copying records. */
    public int channelCount(UUID tunnelId) {
        requireOwningThread();
        return topology.channelCount(Objects.requireNonNull(tunnelId, "tunnelId"));
    }

    /** Returns the network's total channel count in O(1) without copying records. */
    public int channelCount() {
        requireOwningThread();
        return topology.channelCount();
    }

    /** Returns the network's total direct-binding count in O(1) without copying records. */
    public int directBindingCount() {
        requireOwningThread();
        return topology.bindingCount();
    }

    /** Returns the network's total domain-configuration count in O(1) without copying records. */
    public int domainConfigurationCount() {
        requireOwningThread();
        return topology.domainCount();
    }

    /** Checks network-scoped tunnel-name ownership without mutation. */
    public boolean containsTunnelName(ManagedName name) {
        requireOwningThread();
        return topology.containsTunnelName(Objects.requireNonNull(name, "name"));
    }

    /** Checks tunnel-scoped channel-name ownership without mutation. */
    public boolean containsChannelName(UUID tunnelId, ManagedName name) {
        requireOwningThread();
        return topology.containsChannelName(
                Objects.requireNonNull(tunnelId, "tunnelId"), Objects.requireNonNull(name, "name"));
    }

    /** Returns the number of direct configurations below one tunnel for a user-driven management summary. */
    public int bindingCountForTunnel(UUID tunnelId) {
        requireOwningThread();
        return topology.bindingCountForTunnel(Objects.requireNonNull(tunnelId, "tunnelId"));
    }

    /** Returns exact input/output participation counts for one channel. */
    public NetworkTopologyIndex.DirectionCounts channelDirectionCounts(UUID channelId) {
        requireOwningThread();
        return topology.directionCounts(Objects.requireNonNull(channelId, "channelId"));
    }

    /** Returns all tunnels in stable number/UUID order as an independently owned immutable list. */
    public List<NetworkTunnelRecord> tunnels() {
        requireOwningThread();
        return topology.tunnels();
    }

    /** Returns one tunnel's channels in stable order; an absent tunnel has no visible channels. */
    public List<NetworkChannelRecord> channels(UUID tunnelId) {
        requireOwningThread();
        return topology.channels(Objects.requireNonNull(tunnelId, "tunnelId"));
    }

    /** Returns one node's direct bindings in stable channel order without exposing the mutable backing list. */
    public List<DirectNodeBinding> directBindings(UUID nodeId) {
        requireOwningThread();
        return topology.bindings(Objects.requireNonNull(nodeId, "nodeId"));
    }

    /** O(1) exact binding lookup on the owning thread, without snapshots, simulation or mutation. */
    public Optional<DirectNodeBinding> findDirectBinding(UUID nodeId, UUID channelId) {
        requireOwningThread();
        return topology.findBinding(nodeId, channelId);
    }

    /**
     * Returns the tunnel derived from one node's direct bindings, or empty when the node has none.
     * This owning-server-thread query neither simulates nor mutates SavedData, indexes or dirty state; null and
     * wrong-thread access reject before returning authority.
     */
    public Optional<UUID> directTunnelId(UUID nodeId) {
        requireOwningThread();
        return topology.directTunnelId(Objects.requireNonNull(nodeId, "nodeId"));
    }

    /** Returns one immutable domain direction without creation or mutation. */
    public Optional<DomainNodeConfiguration> domainConfiguration(UUID nodeId) {
        requireOwningThread();
        return topology.domain(Objects.requireNonNull(nodeId, "nodeId"));
    }

    /** Finds one tunnel by stable identity without mutation. */
    public Optional<NetworkTunnelRecord> findTunnel(UUID tunnelId) {
        requireOwningThread();
        return topology.findTunnel(Objects.requireNonNull(tunnelId, "tunnelId"));
    }

    /** Finds one channel by stable identity without mutation. */
    public Optional<NetworkChannelRecord> findChannel(UUID channelId) {
        requireOwningThread();
        return topology.findChannel(Objects.requireNonNull(channelId, "channelId"));
    }

    /** Pages tunnels from the incrementally maintained network-local order. */
    public NetworkTopologyIndex.Page<NetworkTunnelRecord> pageTunnels(
            @Nullable UUID anchor, boolean backwards, int limit) {
        requireOwningThread();
        return topology.pageTunnels(anchor, backwards, limit);
    }

    /** Pages channels from one tunnel-local order. */
    public NetworkTopologyIndex.Page<NetworkChannelRecord> pageChannels(
            UUID tunnelId, @Nullable UUID anchor, boolean backwards, int limit) {
        requireOwningThread();
        return topology.pageChannels(tunnelId, anchor, backwards, limit);
    }

    /** Returns a nonreserving network-scoped display-name suggestion from the complete tunnel name index. */
    public ManagedName suggestedTunnelName(io.github.loongin.omniresonance.network.ManagedNamePrefix prefix) {
        requireOwningThread();
        return topology.suggestedTunnelName(Objects.requireNonNull(prefix, "prefix"));
    }

    /** Returns a nonreserving tunnel-scoped display-name suggestion from the complete channel name index. */
    public ManagedName suggestedChannelName(
            UUID tunnelId, io.github.loongin.omniresonance.network.ManagedNamePrefix prefix) {
        requireOwningThread();
        return topology.suggestedChannelName(
                Objects.requireNonNull(tunnelId, "tunnelId"), Objects.requireNonNull(prefix, "prefix"));
    }

    /** Atomically creates one network-scoped tunnel and its mandatory initial channel after complete preflight. */
    public TunnelCreation createTunnel(
            UUID tunnelId, ManagedName name, UUID initialChannelId, ManagedName initialChannelName, int gameplayLimit) {
        requireOwningThread();
        Objects.requireNonNull(tunnelId, "tunnelId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(initialChannelId, "initialChannelId");
        Objects.requireNonNull(initialChannelName, "initialChannelName");
        validateQuota(gameplayLimit, 0, 65535, "tunnel");
        int count = topology.tunnelCount();
        if (count >= 65535 || (gameplayLimit != -1 && count >= gameplayLimit)) {
            throw new IllegalArgumentException("Network tunnel quota reached");
        }
        long number = Math.incrementExact(lastTunnelNumber);
        long nextTopologyRevision = nextTopologyRevision();
        NetworkTunnelRecord tunnel = NetworkTunnelRecord.freshWithInitialChannel(tunnelId, number, name);
        NetworkChannelRecord initialChannel =
                NetworkChannelRecord.fresh(initialChannelId, tunnelId, 1, initialChannelName);
        topology.addTunnelWithInitialChannel(tunnel, initialChannel);
        lastTunnelNumber = number;
        topologyRevision = nextTopologyRevision;
        setDirty();
        return new TunnelCreation(tunnel, initialChannel);
    }

    /** Renames one exact-revision tunnel; missing or stale identity returns empty and no-op stays clean. */
    public Optional<NetworkTunnelRecord> renameTunnel(UUID tunnelId, long expectedRevision, ManagedName name) {
        requireOwningThread();
        Objects.requireNonNull(tunnelId, "tunnelId");
        Objects.requireNonNull(name, "name");
        Optional<NetworkTunnelRecord> currentResult = currentTunnelAtRevision(tunnelId, expectedRevision);
        if (currentResult.isEmpty()) {
            return Optional.empty();
        }
        NetworkTunnelRecord current = currentResult.orElseThrow();
        NetworkTunnelRecord updated = current.withName(name);
        if (updated == current) {
            return Optional.of(current);
        }
        long nextTopologyRevision = nextTopologyRevision();
        topology.replaceTunnel(current, updated);
        topologyRevision = nextTopologyRevision;
        setDirty();
        return Optional.of(updated);
    }

    /** Changes the unique tunnel switch without modifying channels, bindings or node chunk-loading requests. */
    public Optional<NetworkTunnelRecord> setTunnelEnabled(UUID tunnelId, long expectedRevision, boolean enabled) {
        requireOwningThread();
        Objects.requireNonNull(tunnelId, "tunnelId");
        Optional<NetworkTunnelRecord> currentResult = currentTunnelAtRevision(tunnelId, expectedRevision);
        if (currentResult.isEmpty()) {
            return Optional.empty();
        }
        NetworkTunnelRecord current = currentResult.orElseThrow();
        NetworkTunnelRecord updated = current.withEnabled(enabled);
        if (updated == current) {
            return Optional.of(current);
        }
        long nextTopologyRevision = nextTopologyRevision();
        topology.replaceTunnel(current, updated);
        topologyRevision = nextTopologyRevision;
        setDirty();
        return Optional.of(updated);
    }

    /** Creates one tunnel-local channel and advances the tunnel's nonreusable channel number exactly once. */
    public NetworkChannelRecord createChannel(
            UUID tunnelId, long expectedTunnelRevision, UUID channelId, ManagedName name, int gameplayLimit) {
        requireOwningThread();
        Objects.requireNonNull(tunnelId, "tunnelId");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(name, "name");
        validateQuota(gameplayLimit, 0, 65535, "channel");
        NetworkTunnelRecord current = currentTunnelAtRevision(tunnelId, expectedTunnelRevision)
                .orElseThrow(() -> new IllegalStateException("Missing or stale tunnel"));
        int count = topology.channelCount(tunnelId);
        if (count >= 65535 || (gameplayLimit != -1 && count >= gameplayLimit)) {
            throw new IllegalArgumentException("Tunnel channel quota reached");
        }
        long channelNumber = Math.incrementExact(current.lastChannelNumber());
        NetworkTunnelRecord updatedTunnel = current.withLastChannelNumber(channelNumber);
        NetworkChannelRecord channel = NetworkChannelRecord.fresh(channelId, tunnelId, channelNumber, name);
        long nextTopologyRevision = nextTopologyRevision();
        topology.addChannel(channel);
        topology.replaceTunnel(current, updatedTunnel);
        topologyRevision = nextTopologyRevision;
        setDirty();
        return channel;
    }

    /** Renames one exact-revision channel within its tunnel-local uniqueness scope. */
    public Optional<NetworkChannelRecord> renameChannel(UUID channelId, long expectedRevision, ManagedName name) {
        requireOwningThread();
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(name, "name");
        Optional<NetworkChannelRecord> currentResult = currentChannelAtRevision(channelId, expectedRevision);
        if (currentResult.isEmpty()) {
            return Optional.empty();
        }
        NetworkChannelRecord current = currentResult.orElseThrow();
        NetworkChannelRecord updated = current.withName(name);
        if (updated == current) {
            return Optional.of(current);
        }
        long nextTopologyRevision = nextTopologyRevision();
        topology.replaceChannel(current, updated);
        topologyRevision = nextTopologyRevision;
        setDirty();
        return Optional.of(updated);
    }

    /** Returns an immutable impact snapshot for one existing channel without mutation. */
    public TopologyDeletionImpact summarizeChannelDeletion(UUID channelId) {
        requireOwningThread();
        UUID requiredId = Objects.requireNonNull(channelId, "channelId");
        requireRemovableChannel(requiredId);
        return topology.summarizeChannel(requiredId, topologyRevision);
    }

    /** Returns an immutable impact snapshot for one existing tunnel without mutation. */
    public TopologyDeletionImpact summarizeTunnelDeletion(UUID tunnelId) {
        requireOwningThread();
        return topology.summarizeTunnel(Objects.requireNonNull(tunnelId, "tunnelId"), topologyRevision);
    }

    /** Deletes one channel and its bindings only at the exact object and topology revisions. */
    public List<NetworkNodeRecord> deleteChannel(UUID channelId, long expectedRevision, long expectedTopologyRevision) {
        requireOwningThread();
        NetworkChannelRecord channel = currentChannelAtRevision(
                        Objects.requireNonNull(channelId, "channelId"), expectedRevision)
                .orElseThrow(() -> new IllegalStateException("Missing or stale channel"));
        requireRemovableChannel(channel.channelId());
        if (expectedTopologyRevision != topologyRevision) {
            throw new IllegalStateException("Stale topology deletion summary");
        }
        TopologyDeletionImpact impact = topology.summarizeChannel(channel.channelId(), topologyRevision);
        return commitDeletion(impact, () -> topology.removeChannel(channel.channelId()));
    }

    private void requireRemovableChannel(UUID channelId) {
        NetworkChannelRecord channel =
                topology.findChannel(channelId).orElseThrow(() -> new IllegalArgumentException("Missing channel"));
        if (topology.channelCount(channel.tunnelId()) <= 1) {
            throw new IllegalStateException("Cannot remove the final tunnel channel");
        }
    }

    /** Deletes one tunnel, all child channels and their bindings at exact object/topology revisions. */
    public List<NetworkNodeRecord> deleteTunnel(UUID tunnelId, long expectedRevision, long expectedTopologyRevision) {
        requireOwningThread();
        NetworkTunnelRecord tunnel = currentTunnelAtRevision(
                        Objects.requireNonNull(tunnelId, "tunnelId"), expectedRevision)
                .orElseThrow(() -> new IllegalStateException("Missing or stale tunnel"));
        if (expectedTopologyRevision != topologyRevision) {
            throw new IllegalStateException("Stale topology deletion summary");
        }
        TopologyDeletionImpact impact = topology.summarizeTunnel(tunnel.tunnelId(), topologyRevision);
        return commitDeletion(impact, () -> topology.removeTunnel(tunnel.tunnelId()));
    }

    /** Direction-only compatibility save preserves existing common fields and applies confirmed exclusive-field reset. */
    public NetworkNodeRecord setDirectBinding(
            UUID nodeId,
            long expectedNodeRevision,
            UUID channelId,
            TransferDirection direction,
            boolean confirmedReset,
            int gameplayLimit) {
        requireOwningThread();
        Objects.requireNonNull(direction, "direction");
        io.github.loongin.omniresonance.transfer.StoredResourcePolicy policy = topology.findBinding(
                        Objects.requireNonNull(nodeId, "nodeId"), Objects.requireNonNull(channelId, "channelId"))
                .map(DirectNodeBinding::storedPolicy)
                .map(current -> current.switchDirection(direction))
                .orElseGet(() -> new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(
                        io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.defaults(direction), Map.of()));
        return setDirectBinding(nodeId, expectedNodeRevision, channelId, policy, confirmedReset, gameplayLimit);
    }

    /** Creates or changes one direct direction after exact node revision, mode, reset and quota validation. */
    public NetworkNodeRecord setDirectBinding(
            UUID nodeId,
            long expectedNodeRevision,
            UUID channelId,
            io.github.loongin.omniresonance.transfer.ItemTransferPolicy policy,
            boolean confirmedReset,
            int gameplayLimit) {
        return setDirectBinding(
                nodeId,
                expectedNodeRevision,
                channelId,
                new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(
                        io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.legacy(policy), Map.of()),
                confirmedReset,
                gameplayLimit);
    }

    /** Creates or changes one direct direction after exact node revision, mode, reset and quota validation. */
    public NetworkNodeRecord setDirectBinding(
            UUID nodeId,
            long expectedNodeRevision,
            UUID channelId,
            io.github.loongin.omniresonance.transfer.StoredResourcePolicy policy,
            boolean confirmedReset,
            int gameplayLimit) {
        requireOwningThread();
        WorkingFaces faces = topology.findBinding(nodeId, channelId)
                .map(DirectNodeBinding::workingFaces)
                .orElseGet(() -> nodes.get(nodeId).form() == io.github.loongin.omniresonance.node.NodeForm.PANEL
                        ? WorkingFaces.attachedFace()
                        : WorkingFaces.explicit(0));
        return setDirectBinding(nodeId, expectedNodeRevision, channelId, policy, faces, confirmedReset, gameplayLimit);
    }

    /** Explicit legacy item migration; the resulting resource policy is the only stored authority. */
    public NetworkNodeRecord setDirectBinding(
            UUID nodeId,
            long revision,
            UUID channelId,
            io.github.loongin.omniresonance.transfer.ItemTransferPolicy policy,
            WorkingFaces faces,
            boolean confirmed,
            int limit) {
        return setDirectBinding(
                nodeId,
                revision,
                channelId,
                new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(
                        io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.legacy(policy), Map.of()),
                faces,
                confirmed,
                limit);
    }

    /** Atomically saves owned immutable policy/faces on the server thread; validation failures leave all authority unchanged. */
    public NetworkNodeRecord setDirectBinding(
            UUID nodeId,
            long expectedNodeRevision,
            UUID channelId,
            io.github.loongin.omniresonance.transfer.StoredResourcePolicy policy,
            WorkingFaces faces,
            boolean confirmedReset,
            int gameplayLimit) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(faces, "faces");
        ResourcePolicyNbt.encode(policy);
        validateQuota(gameplayLimit, 1, 1024, "direct binding");
        NetworkNodeRecord node = currentAtRevision(nodeId, expectedNodeRevision)
                .orElseThrow(() -> new IllegalStateException("Missing or stale node"));
        if (node.mode() != NodeMode.DIRECT || topology.findChannel(channelId).isEmpty()) {
            throw new IllegalStateException("Direct binding requires a direct node and existing channel");
        }
        faces.validate(node.form());
        Optional<DirectNodeBinding> existing = topology.findBinding(nodeId, channelId);
        if (existing.isPresent()
                && existing.orElseThrow().storedPolicy().equals(policy)
                && existing.orElseThrow().workingFaces().equals(faces)) {
            return node;
        }
        if (existing.isPresent()
                && existing.orElseThrow().direction()
                        != policy.effectivePolicy().direction()
                && !confirmedReset) {
            throw new IllegalStateException("Changing direction requires reset confirmation");
        }
        if (existing.isEmpty()
                && (topology.bindingCount(nodeId) >= 1024
                        || (gameplayLimit != -1 && topology.bindingCount(nodeId) >= gameplayLimit))) {
            throw new IllegalArgumentException("Direct binding quota reached");
        }
        NetworkNodeRecord updated = node.withConfigurationChanged();
        long nextTopologyRevision = nextTopologyRevision();
        topology.putBinding(new DirectNodeBinding(nodeId, channelId, policy, faces));
        nodes.put(nodeId, updated);
        topologyRevision = nextTopologyRevision;
        setDirty();
        return updated;
    }

    /**
     * Clears every binding of one exact DIRECT node before navigation to another enabled tunnel.
     * This owning-server-thread mutation is not a simulation: it validates node/topology revisions, target state and
     * both revision increments before one in-memory commit. Any failure leaves bindings, node, revisions and dirty
     * state unchanged; the target tunnel is returned for navigation but is not persisted on the node.
     */
    public TunnelSwitchResult switchDirectTunnel(
            UUID nodeId, long expectedNodeRevision, UUID targetTunnelId, long expectedTopologyRevision) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(targetTunnelId, "targetTunnelId");
        NetworkNodeRecord node = currentAtRevision(nodeId, expectedNodeRevision)
                .orElseThrow(() -> new IllegalStateException("Missing or stale node"));
        NetworkTunnelRecord target = topology.findTunnel(targetTunnelId)
                .orElseThrow(() -> new IllegalArgumentException("Missing target tunnel"));
        UUID currentTunnel = topology.directTunnelId(nodeId)
                .orElseThrow(() -> new IllegalStateException("Node has no direct tunnel"));
        if (node.mode() != NodeMode.DIRECT
                || expectedTopologyRevision != topologyRevision
                || currentTunnel.equals(targetTunnelId)
                || !target.enabled()) {
            throw new IllegalStateException("Invalid direct tunnel switch");
        }
        int expectedRemoved = topology.bindingCount(nodeId);
        if (expectedRemoved < 1) {
            throw new IllegalStateException("Node has no direct bindings");
        }
        NetworkNodeRecord updated = node.withConfigurationChanged();
        long nextTopologyRevision = nextTopologyRevision();

        topology.removeBindings(nodeId);
        nodes.put(nodeId, updated);
        topologyRevision = nextTopologyRevision;
        setDirty();
        return new TunnelSwitchResult(updated, target.tunnelId(), expectedRemoved);
    }

    /** Removes one exact node/channel binding; a missing binding is an unchanged node result. */
    public Optional<NetworkNodeRecord> removeDirectBinding(UUID nodeId, long expectedNodeRevision, UUID channelId) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(channelId, "channelId");
        Optional<NetworkNodeRecord> currentResult = currentAtRevision(nodeId, expectedNodeRevision);
        if (currentResult.isEmpty()) {
            return Optional.empty();
        }
        NetworkNodeRecord node = currentResult.orElseThrow();
        if (topology.findBinding(nodeId, channelId).isEmpty()) {
            return Optional.of(node);
        }
        NetworkNodeRecord updated = node.withConfigurationChanged();
        long nextTopologyRevision = nextTopologyRevision();
        topology.removeBinding(nodeId, channelId);
        nodes.put(nodeId, updated);
        topologyRevision = nextTopologyRevision;
        setDirty();
        return Optional.of(updated);
    }

    /** Creates or changes the unique domain direction for one exact DOMAIN node. */
    public NetworkNodeRecord setDomainConfiguration(
            UUID nodeId, long expectedNodeRevision, TransferDirection direction, boolean confirmedReset) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(direction, "direction");
        NetworkNodeRecord node = currentAtRevision(nodeId, expectedNodeRevision)
                .orElseThrow(() -> new IllegalStateException("Missing or stale node"));
        if (node.mode() != NodeMode.DOMAIN) {
            throw new IllegalStateException("Domain configuration requires a domain node");
        }
        Optional<DomainNodeConfiguration> existing = topology.domain(nodeId);
        if (existing.isPresent() && existing.orElseThrow().direction() == direction) {
            return node;
        }
        if (existing.isPresent() && !confirmedReset) {
            throw new IllegalStateException("Changing direction requires reset confirmation");
        }
        NetworkNodeRecord updated = node.withConfigurationChanged();
        long nextTopologyRevision = nextTopologyRevision();
        DomainNodeConfiguration previous = existing.orElse(null);
        topology.putDomain(
                previous == null
                        ? new DomainNodeConfiguration(
                                nodeId,
                                new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(
                                        io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.defaults(
                                                direction),
                                        Map.of()),
                                node.form() == NodeForm.PANEL ? WorkingFaces.attachedFace() : WorkingFaces.explicit(0),
                                false)
                        : new DomainNodeConfiguration(
                                nodeId,
                                previous.storedPolicy().switchDirection(direction),
                                previous.workingFaces(),
                                previous.configured()));
        nodes.put(nodeId, updated);
        topologyRevision = nextTopologyRevision;
        setDirty();
        return updated;
    }

    /**
     * Saves a complete domain configuration on the owner thread, clearing the legacy pending state only here.
     * Validates policy encoding, node revision/mode, faces and direction confirmation before mutation. No
     * simulation, permission checks, world access or disk I/O; no-op saves do not dirty or increment revisions.
     */
    public NetworkNodeRecord saveDomainConfiguration(
            UUID nodeId,
            long expectedNodeRevision,
            io.github.loongin.omniresonance.transfer.StoredResourcePolicy policy,
            WorkingFaces faces,
            boolean confirmedReset) {
        requireOwningThread();
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(faces, "faces");
        ResourcePolicyNbt.encode(policy);
        NetworkNodeRecord node = currentAtRevision(Objects.requireNonNull(nodeId), expectedNodeRevision)
                .orElseThrow(() -> new IllegalStateException("Missing or stale node"));
        if (node.mode() != NodeMode.DOMAIN)
            throw new IllegalStateException("Domain configuration requires a domain node");
        faces.validate(node.form());
        DomainNodeConfiguration previous = topology.domain(nodeId).orElse(null);
        DomainNodeConfiguration next = new DomainNodeConfiguration(nodeId, policy, faces, true);
        if (next.equals(previous)) return node;
        if (previous != null && previous.direction() != next.direction() && !confirmedReset)
            throw new IllegalStateException("Changing direction requires reset confirmation");
        NetworkNodeRecord updated = node.withConfigurationChanged();
        long nextRevision = nextTopologyRevision();
        topology.putDomain(next);
        nodes.put(nodeId, updated);
        topologyRevision = nextRevision;
        setDirty();
        return updated;
    }

    /** Removes the unique domain direction; a missing configuration is an unchanged node result. */
    public Optional<NetworkNodeRecord> removeDomainConfiguration(UUID nodeId, long expectedNodeRevision) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Optional<NetworkNodeRecord> currentResult = currentAtRevision(nodeId, expectedNodeRevision);
        if (currentResult.isEmpty()) {
            return Optional.empty();
        }
        NetworkNodeRecord node = currentResult.orElseThrow();
        if (topology.domain(nodeId).isEmpty()) {
            return Optional.of(node);
        }
        NetworkNodeRecord updated = node.withConfigurationChanged();
        long nextTopologyRevision = nextTopologyRevision();
        topology.removeDomain(nodeId);
        nodes.put(nodeId, updated);
        topologyRevision = nextTopologyRevision;
        setDirty();
        return Optional.of(updated);
    }

    /** Finds one immutable node record without mutation or implicit creation. */
    public Optional<NetworkNodeRecord> findNode(UUID nodeId) {
        requireOwningThread();
        return Optional.ofNullable(nodes.get(Objects.requireNonNull(nodeId, "nodeId")));
    }

    /** Returns whether this network owns the normalized node name without mutation, simulation or world access. */
    public boolean containsNodeName(ManagedName name) {
        requireOwningThread();
        return nodesByName.containsKey(Objects.requireNonNull(name, "name").uniquenessKey());
    }

    /**
     * Prevalidates removal of one exact source node and all of its mode configuration without mutation.
     * The returned value is meaningful only to {@link #commitNodeMoveOut(PreparedNodeMoveOut)} on this instance.
     */
    public PreparedNodeMoveOut prepareNodeMoveOut(UUID nodeId, long expectedRevision, GlobalPos expectedPosition) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(expectedPosition, "expectedPosition");
        NetworkNodeRecord source = currentAtRevision(nodeId, expectedRevision)
                .filter(record -> record.position().equals(expectedPosition))
                .orElseThrow(() -> new IllegalStateException("Missing, stale or relocated source node"));
        return new PreparedNodeMoveOut(this, source, nextTopologyRevision());
    }

    /**
     * Prevalidates one target insertion, number allocation, name/identity/position uniqueness and all arithmetic.
     * This method owns no source state and performs no mutation.
     */
    public PreparedNodeMoveIn prepareNodeMoveIn(NetworkNodeRecord source, ManagedName targetName) {
        requireOwningThread();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(targetName, "targetName");
        if (nodes.size() >= NetworkNodeNbt.MAXIMUM_NODES
                || nodes.containsKey(source.nodeId())
                || nodesByName.containsKey(targetName.uniquenessKey())
                || nodesByPosition.containsKey(source.position())) {
            throw new IllegalArgumentException("Target network cannot accept moved node identity, name or position");
        }
        long nodeNumber = Math.incrementExact(lastNodeNumber);
        long nextTopologyRevision = nextTopologyRevision();
        NetworkNodeRecord moved = source.moveTo(nodeNumber, targetName);
        return new PreparedNodeMoveIn(this, moved, nodeNumber, nextTopologyRevision);
    }

    /** Applies only a value prepared by this exact source instance; it performs no validation or external calls. */
    public void commitNodeMoveOut(PreparedNodeMoveOut prepared) {
        requireOwningThread();
        if (Objects.requireNonNull(prepared, "prepared").owner != this) {
            throw new IllegalArgumentException("Prepared source move belongs to another network");
        }
        NetworkNodeRecord source = prepared.source;
        topology.removeNodeConfigurations(source.nodeId());
        nodes.remove(source.nodeId());
        nodesByName.remove(source.name().uniquenessKey());
        nodesByPosition.remove(source.position());
        topologyRevision = prepared.nextTopologyRevision;
        setDirty();
    }

    /** Applies only a value prepared by this exact target instance; it performs no validation or external calls. */
    public void commitNodeMoveIn(PreparedNodeMoveIn prepared) {
        requireOwningThread();
        if (Objects.requireNonNull(prepared, "prepared").owner != this) {
            throw new IllegalArgumentException("Prepared target move belongs to another network");
        }
        NetworkNodeRecord moved = prepared.moved;
        nodes.put(moved.nodeId(), moved);
        nodesByName.put(moved.name().uniquenessKey(), moved.nodeId());
        nodesByPosition.put(moved.position(), moved.nodeId());
        lastNodeNumber = prepared.nextNodeNumber;
        topologyRevision = prepared.nextTopologyRevision;
        setDirty();
    }

    /**
     * Creates one authoritative record after validating capacity and all network-scoped unique keys.
     * Failure occurs before number/index/dirty mutation; this method performs no player authorization or I/O.
     */
    public NetworkNodeRecord createNode(
            UUID nodeId, ManagedName name, GlobalPos position, NodeForm form, Direction facing) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(facing, "facing");
        if (nodes.size() >= NetworkNodeNbt.MAXIMUM_NODES) {
            throw new IllegalArgumentException("Network node hard limit reached");
        }
        if (nodes.containsKey(nodeId)
                || nodesByName.containsKey(name.uniquenessKey())
                || nodesByPosition.containsKey(position)) {
            throw new IllegalArgumentException("Duplicate network node identity, name or position");
        }
        long nodeNumber = Math.incrementExact(lastNodeNumber);
        NetworkNodeRecord record = NetworkNodeRecord.fresh(nodeId, nodeNumber, name, position, form, facing);
        nodes.put(nodeId, record);
        nodesByName.put(name.uniquenessKey(), nodeId);
        nodesByPosition.put(position, nodeId);
        lastNodeNumber = nodeNumber;
        setDirty();
        return record;
    }

    /**
     * Replaces only form/facing for an exact UUID/location match and dirties only when either snapshot changes.
     * Missing or mismatched records return empty without mutation, simulation or world access.
     */
    public Optional<NetworkNodeRecord> updateNodePhysicalSnapshot(
            UUID nodeId, GlobalPos position, NodeForm form, Direction facing) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(facing, "facing");
        NetworkNodeRecord current = nodes.get(nodeId);
        if (current == null || !current.position().equals(position)) {
            return Optional.empty();
        }
        if (current.form() != form) {
            for (DirectNodeBinding binding : topology.bindings(nodeId))
                binding.workingFaces().validate(form);
        }
        NetworkNodeRecord updated = current.withPhysicalSnapshot(form, facing);
        if (updated != current) {
            nodes.put(nodeId, updated);
            setDirty();
        }
        return Optional.of(updated);
    }

    /**
     * Renames one exact-revision node while maintaining network-scoped name uniqueness.
     *
     * <p>This server-thread mutation performs no player authorization, simulation, world access or I/O. Missing or
     * stale records return empty. A conflicting name or revision overflow rejects before changing either name index,
     * the record map or dirty state; an unchanged name returns the same record without dirtying.
     */
    public Optional<NetworkNodeRecord> renameNode(UUID nodeId, long expectedRevision, ManagedName name) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(name, "name");
        Optional<NetworkNodeRecord> currentResult = currentAtRevision(nodeId, expectedRevision);
        if (currentResult.isEmpty()) {
            return Optional.empty();
        }
        NetworkNodeRecord current = currentResult.orElseThrow();
        if (current.name().equals(name)) {
            return Optional.of(current);
        }
        UUID conflictingNode = nodesByName.get(name.uniquenessKey());
        if (conflictingNode != null && !conflictingNode.equals(nodeId)) {
            throw new IllegalArgumentException("Duplicate network node name");
        }
        NetworkNodeRecord updated = current.withName(name);
        nodesByName.remove(current.name().uniquenessKey());
        nodes.put(nodeId, updated);
        nodesByName.put(name.uniquenessKey(), nodeId);
        setDirty();
        return Optional.of(updated);
    }

    /**
     * Changes the unique node enabled state for an exact revision without changing stored configuration.
     * Missing/stale records return empty; no-op changes stay clean. This mutates no world, ticket or client state and
     * performs no player authorization or simulation.
     */
    public Optional<NetworkNodeRecord> setNodeEnabled(UUID nodeId, long expectedRevision, boolean enabled) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Optional<NetworkNodeRecord> currentResult = currentAtRevision(nodeId, expectedRevision);
        if (currentResult.isEmpty()) {
            return Optional.empty();
        }
        NetworkNodeRecord current = currentResult.orElseThrow();
        return replaceNode(current, current.withEnabled(enabled));
    }

    /**
     * Changes the persisted chunk-loading request for one enabled exact-revision node without granting a ticket.
     * Missing/stale records return empty; disabled nodes reject before mutation and no-op changes stay clean. This
     * method performs no player authorization, simulation, world access or I/O.
     */
    public Optional<NetworkNodeRecord> setNodeChunkLoadingRequested(
            UUID nodeId, long expectedRevision, boolean requested) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Optional<NetworkNodeRecord> currentResult = currentAtRevision(nodeId, expectedRevision);
        if (currentResult.isEmpty()) {
            return Optional.empty();
        }
        NetworkNodeRecord current = currentResult.orElseThrow();
        if (!current.enabled()) {
            throw new IllegalStateException("Disabled node cannot change chunk-loading request");
        }
        return replaceNode(current, current.withChunkLoadingRequested(requested));
    }

    /**
     * Changes one enabled node to a configured mode after exact-revision and reset-intent validation.
     * UNCONFIGURED is not a player edit target. DIRECT/DOMAIN transitions require explicit reset confirmation;
     * missing/stale records return empty and every rejection occurs before mutation. No mode configuration is created.
     */
    public Optional<NetworkNodeRecord> setNodeMode(
            UUID nodeId, long expectedRevision, NodeMode mode, boolean confirmedReset) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(mode, "mode");
        if (mode == NodeMode.UNCONFIGURED) {
            throw new IllegalArgumentException("Unconfigured mode cannot be selected");
        }
        Optional<NetworkNodeRecord> currentResult = currentAtRevision(nodeId, expectedRevision);
        if (currentResult.isEmpty()) {
            return Optional.empty();
        }
        NetworkNodeRecord current = currentResult.orElseThrow();
        if (!current.enabled()) {
            throw new IllegalStateException("Disabled node cannot change mode");
        }
        if (current.mode() != NodeMode.UNCONFIGURED && current.mode() != mode && !confirmedReset) {
            throw new IllegalStateException("Changing configured node mode requires reset confirmation");
        }
        NetworkNodeRecord updated = current.withMode(mode);
        if (updated == current) {
            return Optional.of(current);
        }
        long nextTopologyRevision = nextTopologyRevision();
        topology.removeNodeConfigurations(nodeId);
        nodes.put(nodeId, updated);
        topologyRevision = nextTopologyRevision;
        setDirty();
        return Optional.of(updated);
    }

    /**
     * Removes only an exact UUID/location record, preserving historical numbering.
     * Missing/mismatched records return empty without dirtying or changing indexes.
     */
    public Optional<NetworkNodeRecord> removeNode(UUID nodeId, GlobalPos position) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(position, "position");
        NetworkNodeRecord current = nodes.get(nodeId);
        if (current == null || !current.position().equals(position)) {
            return Optional.empty();
        }
        boolean hasConfiguration =
                !topology.bindings(nodeId).isEmpty() || topology.domain(nodeId).isPresent();
        long nextTopologyRevision = hasConfiguration ? nextTopologyRevision() : topologyRevision;
        if (hasConfiguration) {
            topology.removeNodeConfigurations(nodeId);
        }
        nodes.remove(nodeId);
        nodesByName.remove(current.name().uniquenessKey());
        nodesByPosition.remove(current.position());
        topologyRevision = nextTopologyRevision;
        setDirty();
        return Optional.of(current);
    }

    private Optional<NetworkTunnelRecord> currentTunnelAtRevision(UUID tunnelId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected tunnel revision must be nonnegative");
        }
        Optional<NetworkTunnelRecord> current = topology.findTunnel(tunnelId);
        return current.isPresent() && current.orElseThrow().revision() == expectedRevision ? current : Optional.empty();
    }

    private Optional<NetworkChannelRecord> currentChannelAtRevision(UUID channelId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected channel revision must be nonnegative");
        }
        Optional<NetworkChannelRecord> current = topology.findChannel(channelId);
        return current.isPresent() && current.orElseThrow().revision() == expectedRevision ? current : Optional.empty();
    }

    private List<NetworkNodeRecord> commitDeletion(TopologyDeletionImpact impact, Runnable mutation) {
        long nextTopologyRevision = nextTopologyRevision();
        List<NetworkNodeRecord> changed =
                new java.util.ArrayList<>(impact.affectedNodeIds().size());
        for (UUID nodeId : impact.affectedNodeIds()) {
            NetworkNodeRecord current = Objects.requireNonNull(nodes.get(nodeId), "affectedNode");
            changed.add(current.withConfigurationChanged());
        }
        mutation.run();
        for (NetworkNodeRecord updated : changed) {
            nodes.put(updated.nodeId(), updated);
        }
        topologyRevision = nextTopologyRevision;
        setDirty();
        return List.copyOf(changed);
    }

    private long nextTopologyRevision() {
        return Math.incrementExact(topologyRevision);
    }

    private static void validateQuota(int value, int ordinaryMinimum, int hardMaximum, String kind) {
        if (value != -1 && (value < ordinaryMinimum || value > hardMaximum)) {
            throw new IllegalArgumentException("Invalid " + kind + " gameplay quota");
        }
    }

    private Optional<NetworkNodeRecord> currentAtRevision(UUID nodeId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected node revision must be nonnegative");
        }
        NetworkNodeRecord current = nodes.get(nodeId);
        return current == null || current.revision() != expectedRevision ? Optional.empty() : Optional.of(current);
    }

    private Optional<NetworkNodeRecord> replaceNode(NetworkNodeRecord current, NetworkNodeRecord updated) {
        if (updated != current) {
            nodes.put(current.nodeId(), updated);
            setDirty();
        }
        return Optional.of(updated);
    }

    /** Writes current-schema fields into caller-owned tags without changing dirty state or performing I/O. */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        requireOwningThread();
        Objects.requireNonNull(tag, "tag");
        tag.put("audit_entries", AuditNbt.encode(audit.snapshot()));
        tag.putInt("schema_version", ManagedDataNbt.NETWORK_SCHEMA_VERSION);
        tag.putLong("bucket_created_mask", bucketCreatedMask);
        tag.putUUID("network_id", metadata.id());
        tag.putUUID("owner_id", metadata.ownerId());
        tag.putString("name", metadata.name().value());
        tag.putLong("creation_order", metadata.creationOrder());
        tag.put("administrators", ManagedDataNbt.writeAdministrators(metadata.administrators()));
        tag.putLong("management_revision", managementRevision);
        tag.put("recovery", RecoveryNbt.encode(recovery.snapshot()));
        tag.putLong("last_node_number", lastNodeNumber);
        tag.put("nodes", NetworkNodeNbt.encode(nodes.values()));
        tag.putLong("last_tunnel_number", lastTunnelNumber);
        tag.putLong("topology_revision", topologyRevision);
        tag.put("tunnels", NetworkTopologyNbt.encodeTunnels(topology.tunnels()));
        tag.put("channels", NetworkTopologyNbt.encodeChannels(topology.allChannels()));
        tag.put("direct_bindings", NetworkTopologyNbt.encodeDirectBindings(topology.allBindings()));
        tag.put("domain_configurations", NetworkTopologyNbt.encodeDomains(topology.allDomains()));
        return tag;
    }

    /** Installs one owning-server lifecycle observer; callbacks only enqueue immutable keys. */
    public void onRuntimeChanged(@Nullable Runnable listener) {
        requireOwningThread();
        runtimeListener = listener;
    }

    private @Nullable Runnable runtimeListener;
    private @Nullable Runnable recoveryListener;

    /** Installs an owner-thread recovery-only wake observer; it must enqueue without reentering resources. */
    public void onRecoveryChanged(@Nullable Runnable listener) {
        requireOwningThread();
        recoveryListener = listener;
    }

    private void recoveryChanged() {
        requireOwningThread();
        super.setDirty(true);
        if (recoveryListener != null) recoveryListener.run();
    }

    /** Sets owned dirty state without simulation; rejects wrong-thread access before mutation. */
    @Override
    public void setDirty(boolean dirty) {
        requireOwningThread();
        super.setDirty(dirty);
        if (dirty && runtimeListener != null) runtimeListener.run();
    }

    /** Reads owned dirty state without mutation or simulation; rejects wrong-thread access. */
    @Override
    public boolean isDirty() {
        requireOwningThread();
        return super.isDirty();
    }

    private void requireOwningThread() {
        if (Thread.currentThread() != owningThread) {
            throw new IllegalStateException("Network SavedData accessed outside its owning server thread");
        }
    }
}
