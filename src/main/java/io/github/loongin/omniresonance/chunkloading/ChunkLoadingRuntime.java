// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.chunkloading;

import io.github.loongin.omniresonance.config.ServerConfig;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.node.NetworkNodeDirectory;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeAuthorityService;
import io.github.loongin.omniresonance.node.NodeLinkState;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import io.github.loongin.omniresonance.persistence.DomainStorage;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/**
 * Server-lifetime requested-node index and ticket coordinator. Authority callbacks only enqueue network keys;
 * changed-network snapshots are rebuilt at the next tick, never scanned on idle ticks. Current requested nodes
 * own all indexes/check keys; removal and shutdown release them. At most 256 physical-node checks/allocation
 * candidates run per tick, using nonloading lookups; quota-admitted unloaded chunks alone receive tickets.
 */
public final class ChunkLoadingRuntime implements AutoCloseable {
    private static final int WORK = 256;
    private final MinecraftServer server;
    private final SavedNetworkRepository repository;
    private final NetworkDirectory networks;
    private final NetworkNodeDirectory directory;
    private final NodeAuthorityService authority;
    private final ChunkLoadingAllocator allocator;
    private final NodeChunkTickets tickets;
    private final ChunkLoadingReservations reservations = new ChunkLoadingReservations();
    private final Map<UUID, java.util.List<NetworkNodeRecord>> overview = new HashMap<>();
    private final Map<UUID, Tracked> tracked = new HashMap<>();
    private final Map<UUID, Set<UUID>> byNetwork = new HashMap<>();
    private final Map<ChunkLoadingAllocator.Chunk, Set<UUID>> byChunk = new HashMap<>();
    private final LinkedHashSet<UUID> dirty = new LinkedHashSet<>(), checks = new LinkedHashSet<>();
    private final long epoch;
    private long configRevision;
    private boolean closed;
    private io.github.loongin.omniresonance.config.ServerSettings.ChunkLoading settings;

    public ChunkLoadingRuntime(
            MinecraftServer server,
            SavedNetworkRepository repository,
            NetworkDirectory networks,
            NetworkNodeDirectory directory,
            NodeAuthorityService authority,
            ServerConfig.State configuration) {
        this.server = server;
        this.repository = repository;
        this.networks = networks;
        this.directory = directory;
        this.authority = authority;
        epoch = configuration.epoch();
        configRevision = configuration.revision();
        var cfg = configuration.settings().chunkLoading();
        settings = cfg;
        allocator = new ChunkLoadingAllocator(new ChunkLoadingAllocator.Limits(cfg.enabled(), -1, -1));
        tickets = new NodeChunkTickets(server, allocator);
        dirty.addAll(repository.loadedNetworkIds());
        repository.onNetworkChanged(this::changed);
    }

    public void changed(UUID network) {
        check();
        if (!closed) dirty.add(network);
    }

    public void chunkChanged(ChunkLoadingAllocator.Chunk chunk) {
        chunkChanged(chunk, true);
    }

    public void chunkChanged(ChunkLoadingAllocator.Chunk chunk, boolean loaded) {
        check();
        if (closed) return;
        var ids = byChunk.get(chunk);
        if (ids != null)
            for (var id : ids) {
                tracked.get(id).verified = false;
                if (loaded) tracked.get(id).invalid = false;
                checks.addFirst(id);
            }
    }

    public void tick(ServerConfig.State configuration) {
        check();
        if (closed) return;
        applyConfiguration(configuration);
        reconcileChanges();
        int count = settings.enabled() ? Math.min(WORK, checks.size()) : 0;
        for (int i = 0; i < count; i++) verify(checks.removeFirst());
        allocator.advance(WORK);
        ChunkLoadingAllocator.Transition change;
        while ((change = allocator.pollTransition()) != null) {
            if (!change.acquire()) {
                tickets.release(change.chunk());
                continue;
            }
            try {
                if (!tickets.acquire(change.chunk())) {
                    rejectChunk(change.chunk());
                    continue;
                }
                var ids = byChunk.get(change.chunk());
                if (ids != null) for (var id : ids) checks.addFirst(id);
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(ChunkLoadingRuntime.class)
                        .error("Node chunk ticket failed; request remains unavailable", failure);
                rejectChunk(change.chunk());
            }
        }
    }

    private void applyConfiguration(ServerConfig.State configuration) {
        if (configuration.loaded() && configuration.epoch() == epoch && configuration.revision() > configRevision) {
            settings = configuration.settings().chunkLoading();
            configRevision = configuration.revision();
            allocator.limits(new ChunkLoadingAllocator.Limits(settings.enabled(), -1, -1));
        }
    }

    private void reconcileChanges() {
        int changes = dirty.size();
        for (int i = 0; i < changes; i++) rebuild(dirty.removeFirst());
    }
    /** Checks new admission against current authoritative reservations on the server thread, without granting tickets. */
    public ChunkLoadingReservations.Admission admission(
            UUID network, NetworkNodeRecord node, boolean moving, ServerConfig.State configuration) {
        check();
        applyConfiguration(configuration);
        return admission(network, node, moving);
    }

    public ChunkLoadingReservations.Admission admission(UUID network, NetworkNodeRecord node, boolean moving) {
        check();
        if (closed) return ChunkLoadingReservations.Admission.UNAVAILABLE;
        reconcileChanges();
        var metadata = networks.find(network).orElse(null);
        var lookup = directory.byId(node.nodeId());
        if (metadata == null
                || lookup.status() != NetworkNodeDirectory.Status.UNIQUE
                || !lookup.entry().orElseThrow().record().equals(node))
            return ChunkLoadingReservations.Admission.UNAVAILABLE;
        var source = lookup.entry().orElseThrow().networkId();
        var chunk = new ChunkLoadingAllocator.Chunk(
                node.position().dimension().location(),
                node.position().pos().getX() >> 4,
                node.position().pos().getZ() >> 4);
        if (moving && reservations.owns(source, node.nodeId(), metadata.ownerId(), chunk))
            return ChunkLoadingReservations.Admission.ALLOWED;
        var result = reservations.check(
                metadata.ownerId(),
                chunk,
                new ChunkLoadingAllocator.Limits(settings.enabled(), settings.perOwner(), settings.server()));
        if (result != ChunkLoadingReservations.Admission.ALLOWED) return result;
        var level = server.getLevel(node.position().dimension());
        if (!node.enabled()
                || level == null
                || !level.isInWorldBounds(node.position().pos())) return ChunkLoadingReservations.Admission.UNAVAILABLE;
        var loaded = level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
        if (loaded == null
                || !(loaded.getBlockEntity(node.position().pos()) instanceof ResonanceNodeBlockEntity entity)
                || entity.state().isEmpty()
                || !entity.state().orElseThrow().nodeId().equals(node.nodeId())
                || entity.state().orElseThrow().linkState() != NodeLinkState.LINKED)
            return ChunkLoadingReservations.Admission.UNAVAILABLE;
        if (!moving
                && node.mode() == NodeMode.DOMAIN
                && repository.domainStorage(network).activate().isEmpty())
            return ChunkLoadingReservations.Admission.UNAVAILABLE;
        return ChunkLoadingReservations.Admission.ALLOWED;
    }

    private void rebuild(UUID network) {
        var data = repository.findLoadedNetwork(network).orElse(null);
        var metadata = networks.find(network).orElse(null);
        var previous = byNetwork.get(network);
        var retained = new HashSet<UUID>();
        var snapshot = data == null || metadata == null ? java.util.List.<NetworkNodeRecord>of() : data.nodes();
        if (data == null || metadata == null) overview.remove(network);
        else overview.put(network, snapshot);
        if (data != null && metadata != null)
            for (var node : snapshot) {
                if (!node.chunkLoadingRequested()) continue;
                UUID id = node.nodeId();
                retained.add(id);
                reservations.put(
                        network,
                        id,
                        metadata.ownerId(),
                        new ChunkLoadingAllocator.Chunk(
                                node.position().dimension().location(),
                                node.position().pos().getX() >> 4,
                                node.position().pos().getZ() >> 4));
                var old = tracked.get(id);
                if (old != null
                        && old.network.equals(network)
                        && old.owner.equals(metadata.ownerId())
                        && old.node.equals(node)) {
                    apply(old);
                    checks.add(id);
                    continue;
                }
                remove(id);
                var value = new Tracked(network, metadata.ownerId(), node);
                tracked.put(id, value);
                byChunk.computeIfAbsent(value.chunk, ignored -> new HashSet<>()).add(id);
                byNetwork.computeIfAbsent(network, ignored -> new HashSet<>()).add(id);
                apply(value);
                checks.add(id);
            }
        if (previous != null) for (var id : Set.copyOf(previous)) if (!retained.contains(id)) remove(id);
        reservations.retain(network, retained);
    }

    private ChunkLoadingAllocator.Eligibility eligibility(Tracked value) {
        if (!value.node.enabled()) return ChunkLoadingAllocator.Eligibility.NODE_DISABLED;
        var lookup = directory.byId(value.node.nodeId());
        if (lookup.status() != NetworkNodeDirectory.Status.UNIQUE
                || !lookup.entry().orElseThrow().networkId().equals(value.network))
            return ChunkLoadingAllocator.Eligibility.UNAVAILABLE;
        if (!worldPositionAvailable(value)) return ChunkLoadingAllocator.Eligibility.UNAVAILABLE;
        if (value.node.mode() == NodeMode.DOMAIN
                && repository.domainStorage(value.network).state() != DomainStorage.State.AVAILABLE)
            return ChunkLoadingAllocator.Eligibility.DOMAIN_UNAVAILABLE;
        if (value.invalid || server.getLevel(value.node.position().dimension()) == null)
            return ChunkLoadingAllocator.Eligibility.UNAVAILABLE;
        return ChunkLoadingAllocator.Eligibility.READY;
    }

    private boolean worldPositionAvailable(Tracked value) {
        var level = server.getLevel(value.node.position().dimension());
        return level != null && level.isInWorldBounds(value.node.position().pos());
    }

    private void apply(Tracked value) {
        allocator.put(
                new ChunkLoadingAllocator.Request(value.node.nodeId(), value.owner, value.chunk, eligibility(value)));
    }

    private void verify(UUID id) {
        var value = tracked.get(id);
        if (value == null) return;
        if (!value.node.enabled()) return;
        if (!worldPositionAvailable(value)) {
            apply(value);
            return;
        }
        if (value.node.mode() == NodeMode.DOMAIN)
            repository.domainStorage(value.network).activate();
        apply(value);
        if (eligibility(value) != ChunkLoadingAllocator.Eligibility.READY) return;
        var level = server.getLevel(value.node.position().dimension());
        var chunk = level.getChunkSource().getChunkNow(value.chunk.x(), value.chunk.z());
        if (chunk == null) {
            value.verified = false;
            if (allocator.status(id) == ChunkLoadingAllocator.Status.ACTIVE) checks.add(id);
            return;
        }
        authority.verifyRecordedPosition(value.node.position());
        var current = directory.byId(id).entry().orElse(null);
        if (current == null
                || !current.networkId().equals(value.network)
                || !current.record().position().equals(value.node.position())) {
            remove(id);
            return;
        }
        var entity = chunk.getBlockEntity(value.node.position().pos());
        var state =
                entity instanceof ResonanceNodeBlockEntity node ? node.state().orElse(null) : null;
        value.verified = state != null && state.nodeId().equals(id) && state.linkState() == NodeLinkState.LINKED;
        value.invalid = !value.verified;
        apply(value);
        if (value.verified && allocator.status(id) == ChunkLoadingAllocator.Status.ACTIVE) checks.add(id);
    }

    private void rejectChunk(ChunkLoadingAllocator.Chunk chunk) {
        var ids = byChunk.get(chunk);
        if (ids != null)
            for (var id : ids) {
                var value = tracked.get(id);
                value.invalid = true;
                value.verified = false;
                apply(value);
            }
    }

    private void remove(UUID id) {
        var value = tracked.remove(id);
        checks.remove(id);
        allocator.remove(id);
        if (value == null) return;
        var group = byChunk.get(value.chunk);
        group.remove(id);
        if (group.isEmpty()) byChunk.remove(value.chunk);
        var network = byNetwork.get(value.network);
        if (network != null) {
            network.remove(id);
            if (network.isEmpty()) byNetwork.remove(value.network);
        }
    }

    public ChunkLoadingAllocator.Status status(UUID node) {
        check();
        var state = allocator.status(node);
        var value = tracked.get(node);
        if (value != null && value.node.enabled()) {
            if (!settings.enabled()) return ChunkLoadingAllocator.Status.SERVER_DISABLED;
            if (state == ChunkLoadingAllocator.Status.DOMAIN_UNAVAILABLE
                    && repository.domainStorage(value.network).state() == DomainStorage.State.NOT_LOADED) {
                return ChunkLoadingAllocator.Status.QUEUED;
            }
        }
        return state == ChunkLoadingAllocator.Status.ACTIVE && (value == null || !value.verified)
                ? ChunkLoadingAllocator.Status.QUEUED
                : state;
    }

    public record Page(java.util.List<NetworkNodeRecord> entries, boolean previous, boolean next, int total) {
        public Page {
            entries = java.util.List.copyOf(entries);
        }
    }
    /** Bounded overview slice from the latest event-driven metadata snapshot; never scans worlds or activates domains. */
    public Page page(UUID network, long anchor, boolean before) {
        check();
        if (anchor < 0) throw new IllegalArgumentException("Invalid node anchor");
        var rows = overview.getOrDefault(network, java.util.List.of());
        int bound = ChunkOverviewPaging.bound(rows, anchor, before, NetworkNodeRecord::nodeNumber);
        var window = ChunkOverviewPaging.window(rows.size(), bound, before);
        int start = window.start(), end = window.end();
        return new Page(rows.subList(start, end), start > 0, end < rows.size(), rows.size());
    }

    /** Borrowed immutable event snapshot for bounded terminal scans; valid until replaced, no world reads. */
    public java.util.List<NetworkNodeRecord> nodeSnapshot(UUID network) {
        check();
        return overview.getOrDefault(network, java.util.List.of());
    }

    public int physicalCount() {
        check();
        return tickets.size();
    }

    public int ownerCount(UUID owner) {
        check();
        return reservations.ownerCount(owner);
    }

    public int reservedCount() {
        check();
        return reservations.serverCount();
    }

    public void close() {
        check();
        if (closed) return;
        closed = true;
        repository.onNetworkChanged(null);
        tickets.close();
        overview.clear();
        reservations.clear();
        tracked.clear();
        byChunk.clear();
        byNetwork.clear();
        dirty.clear();
        checks.clear();
    }

    private void check() {
        if (!server.isSameThread()) throw new IllegalStateException("Chunk loading accessed off server thread");
    }

    private static final class Tracked {
        final UUID network, owner;
        final NetworkNodeRecord node;
        final ChunkLoadingAllocator.Chunk chunk;
        boolean verified, invalid;

        Tracked(UUID network, UUID owner, NetworkNodeRecord node) {
            this.network = network;
            this.owner = owner;
            this.node = node;
            chunk = new ChunkLoadingAllocator.Chunk(
                    node.position().dimension().location(),
                    node.position().pos().getX() >> 4,
                    node.position().pos().getZ() >> 4);
        }
    }
}
