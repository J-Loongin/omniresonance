// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-thread authority boundary between per-network node records and loaded physical node block entities.
 *
 * <p>The service owns no world or persistence lifecycle beyond the caller-owned server/repository/directory
 * references, which {@link #close()} releases. Trusted linking performs no player authorization; B3 callers must
 * authorize before entry. Reconciliation never loads chunks, scans other faces, simulates transfer, retains block
 * entities, or guesses conflict ownership. All mutations validate their exact inputs before the documented
 * deterministic in-memory commit sequence; unknown external outcomes do not exist in this service.
 */
public final class NodeAuthorityService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeAuthorityService.class);
    private static final int POSITION_CHECKS_PER_TICK = 256;
    private @Nullable MinecraftServer server;
    private @Nullable SavedNetworkRepository repository;
    private @Nullable NetworkNodeDirectory directory;
    private @Nullable Supplier<UUID> nodeIdSource;
    private @Nullable NodeReconciliationQueue workQueue;
    private List<NetworkNodeDirectory.Entry> startupEntries = List.of();
    private int startupCursor;
    private boolean overflowLogged;

    /** Retains one matching server lifecycle and rejects construction outside its server thread. */
    public NodeAuthorityService(
            MinecraftServer server,
            SavedNetworkRepository repository,
            NetworkNodeDirectory directory,
            Supplier<UUID> nodeIdSource) {
        this.server = Objects.requireNonNull(server, "server");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.nodeIdSource = Objects.requireNonNull(nodeIdSource, "nodeIdSource");
        requireServerThread();
        workQueue = NodeReconciliationQueue.production();
    }

    /**
     * Links one exact loaded BLANK node to a healthy network using a caller-authorized managed name.
     *
     * <p>All world, identity and directory preconditions are validated first. The network record commits before
     * the derived directory and physical LINKED hint. Failures before commit leave numbering, indexes and BE state
     * unchanged. This method performs no simulation, client trust, save flush or player-role check.
     */
    public NetworkNodeRecord link(UUID networkId, ResonanceNodeBlockEntity entity, ManagedName name) {
        requireServerThread();
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(name, "name");
        PhysicalNode physical = requirePhysicalNode(entity);
        NodePersistentState.Valid state = entity.state()
                .orElseThrow(() -> new IllegalStateException("Unavailable physical node cannot be linked"));
        if (state.linkState() != NodeLinkState.BLANK) {
            throw new IllegalStateException("Only a blank physical node can be linked");
        }
        NetworkNodeDirectory activeDirectory = directory();
        if (activeDirectory.byId(state.nodeId()).status() != NetworkNodeDirectory.Status.ABSENT
                || activeDirectory.byPosition(physical.position()).status() != NetworkNodeDirectory.Status.ABSENT) {
            throw new IllegalArgumentException("Node identity or position is already authoritative");
        }
        NetworkSavedData network = repository()
                .findLoadedNetwork(networkId)
                .orElseThrow(() -> new IllegalArgumentException("Target network is not available"));
        NetworkNodeRecord record =
                network.createNode(state.nodeId(), name, physical.position(), physical.form(), physical.facing());
        activeDirectory.add(new NetworkNodeDirectory.Entry(networkId, record));
        entity.linkFromAuthority(state.nodeId());
        return record;
    }

    /**
     * Moves one exact loaded LINKED node between two healthy network shards after all identity, physical, revision,
     * target-capacity and directory checks. The generic physical LINKED hint is retained because network ownership
     * exists only in SavedData and the derived directory. This trusted method performs no player authorization.
     */
    public NetworkNodeRecord moveNetwork(
            UUID sourceNetworkId,
            UUID targetNetworkId,
            ResonanceNodeBlockEntity entity,
            long expectedRevision,
            ManagedName targetName) {
        requireServerThread();
        Objects.requireNonNull(sourceNetworkId, "sourceNetworkId");
        Objects.requireNonNull(targetNetworkId, "targetNetworkId");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(targetName, "targetName");
        if (sourceNetworkId.equals(targetNetworkId) || expectedRevision < 0) {
            throw new IllegalArgumentException("Node move requires distinct networks and a valid revision");
        }
        PhysicalNode physical = requirePhysicalNode(entity);
        NodePersistentState.Valid state = entity.state()
                .orElseThrow(() -> new IllegalStateException("Unavailable physical node cannot move networks"));
        if (state.linkState() != NodeLinkState.LINKED) {
            throw new IllegalStateException("Only a linked physical node can move networks");
        }
        NetworkNodeDirectory.Lookup lookup = directory().byId(state.nodeId());
        if (lookup.status() != NetworkNodeDirectory.Status.UNIQUE) {
            throw new IllegalStateException("Moved node authority is not unique");
        }
        NetworkNodeDirectory.Entry previous = lookup.entry().orElseThrow();
        if (!previous.networkId().equals(sourceNetworkId)) {
            throw new IllegalStateException("Moved node no longer belongs to the source network");
        }
        NetworkSavedData source = authoritativeNetwork(sourceNetworkId);
        NetworkSavedData target = authoritativeNetwork(targetNetworkId);
        NetworkNodeRecord sourceRecord = source.findNode(state.nodeId())
                .orElseThrow(() -> new IllegalStateException("Source node authority disappeared"));
        if (!sourceRecord.equals(previous.record())
                || sourceRecord.revision() != expectedRevision
                || !sourceRecord.position().equals(physical.position())
                || sourceRecord.form() != physical.form()
                || sourceRecord.facing() != physical.facing()) {
            throw new IllegalStateException("Source node changed before network move");
        }

        NetworkSavedData.PreparedNodeMoveOut preparedOut =
                source.prepareNodeMoveOut(sourceRecord.nodeId(), expectedRevision, sourceRecord.position());
        NetworkSavedData.PreparedNodeMoveIn preparedIn = target.prepareNodeMoveIn(sourceRecord, targetName);
        NetworkNodeDirectory.Entry updated = new NetworkNodeDirectory.Entry(targetNetworkId, preparedIn.moved());
        NetworkNodeDirectory.PreparedNetworkReplacement preparedDirectory =
                directory().prepareNetworkReplacement(previous, updated);

        source.commitNodeMoveOut(preparedOut);
        target.commitNodeMoveIn(preparedIn);
        directory().commitNetworkReplacement(preparedDirectory);
        return preparedIn.moved();
    }

    /** Reconciles one exact loaded physical node against unique authority without retaining the entity. */
    public void reconcileLoaded(ResonanceNodeBlockEntity entity) {
        requireServerThread();
        Objects.requireNonNull(entity, "entity");
        PhysicalNode physical = physicalNode(entity);
        if (physical == null) {
            return;
        }
        NodePersistentState.Valid state = entity.state().orElse(null);
        if (state == null) {
            return;
        }
        NetworkNodeDirectory activeDirectory = directory();
        NetworkNodeDirectory.Lookup idLookup = activeDirectory.byId(state.nodeId());
        NetworkNodeDirectory.Lookup positionLookup = activeDirectory.byPosition(physical.position());
        if (idLookup.status() == NetworkNodeDirectory.Status.CONFLICTED
                || positionLookup.status() == NetworkNodeDirectory.Status.CONFLICTED) {
            return;
        }

        if (idLookup.status() == NetworkNodeDirectory.Status.UNIQUE) {
            NetworkNodeDirectory.Entry entry = idLookup.entry().orElseThrow();
            if (entry.record().position().equals(physical.position())) {
                refreshExactPhysicalSnapshot(entry, physical);
                entity.linkFromAuthority(state.nodeId());
                return;
            }
        }

        if (idLookup.status() == NetworkNodeDirectory.Status.ABSENT
                && state.linkState() == NodeLinkState.LINKED
                && repository().hasUnreadableNetworkShards()) {
            return;
        }

        if (positionLookup.status() == NetworkNodeDirectory.Status.UNIQUE) {
            removeEntry(positionLookup.entry().orElseThrow());
        }

        if (idLookup.status() == NetworkNodeDirectory.Status.UNIQUE || state.linkState() == NodeLinkState.LINKED) {
            replaceWithUnclaimedBlank(entity, state.nodeId());
        }
    }

    /** Deletes only a unique exact network/UUID/position authority record; conflicts and mismatches are retained. */
    public void removePhysical(UUID nodeId, GlobalPos position) {
        requireServerThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(position, "position");
        NetworkNodeDirectory.Lookup idLookup = directory().byId(nodeId);
        NetworkNodeDirectory.Lookup positionLookup = directory().byPosition(position);
        if (idLookup.status() != NetworkNodeDirectory.Status.UNIQUE
                || positionLookup.status() != NetworkNodeDirectory.Status.UNIQUE) {
            return;
        }
        NetworkNodeDirectory.Entry byId = idLookup.entry().orElseThrow();
        if (byId.equals(positionLookup.entry().orElseThrow())) {
            removeEntry(byId);
        }
    }

    /**
     * Verifies one recorded position only when its dimension and FULL chunk are already loaded.
     * Air/non-node proves a ghost; missing/corrupt BE state remains uncertain and is preserved.
     */
    public void verifyRecordedPosition(GlobalPos position) {
        requireServerThread();
        Objects.requireNonNull(position, "position");
        NetworkNodeDirectory.Lookup lookup = directory().byPosition(position);
        if (lookup.status() != NetworkNodeDirectory.Status.UNIQUE) {
            return;
        }
        ServerLevel level = server().getLevel(position.dimension());
        if (level == null) {
            return;
        }
        BlockPos blockPos = position.pos();
        LevelChunk chunk = level.getChunkSource().getChunkNow(blockPos.getX() >> 4, blockPos.getZ() >> 4);
        if (chunk == null) {
            return;
        }
        BlockState blockState = chunk.getBlockState(blockPos);
        if (!(blockState.getBlock() instanceof AbstractResonanceNodeBlock)) {
            removeEntry(lookup.entry().orElseThrow());
            return;
        }
        BlockEntity blockEntity = chunk.getBlockEntity(blockPos);
        if (blockEntity instanceof ResonanceNodeBlockEntity node && node.state().isPresent()) {
            reconcileLoaded(node);
        }
    }

    /** Starts or restarts one finite stable sweep over every currently unique authority record. */
    public void beginStartupSweep() {
        requireServerThread();
        startupEntries = directory().allUniqueEntries();
        startupCursor = 0;
    }

    /** Enqueues one immutable position for later verification without retaining a world or loading a chunk. */
    public void enqueuePosition(GlobalPos position) {
        requireServerThread();
        handleOffer(queue().offerPosition(position));
    }

    /** Enqueues one immutable loaded-chunk observation; actual access begins only during a later tick. */
    public void enqueueChunk(
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, int x, int z) {
        requireServerThread();
        handleOffer(queue().offerChunk(dimension, x, z));
    }

    /** Advances at most 256 recorded-position checks and then yields until the next server gt. */
    public void tick() {
        requireServerThread();
        int remaining = POSITION_CHECKS_PER_TICK;
        while (remaining > 0) {
            if (startupCursor < startupEntries.size()) {
                verifyRecordedPosition(
                        startupEntries.get(startupCursor++).record().position());
                remaining--;
                continue;
            }
            if (!startupEntries.isEmpty()) {
                startupEntries = List.of();
                startupCursor = 0;
            }
            NodeReconciliationQueue.Work work = queue().poll().orElse(null);
            if (work == null) {
                break;
            }
            if (work instanceof NodeReconciliationQueue.Position position) {
                verifyRecordedPosition(position.position());
                remaining--;
            } else if (work instanceof NodeReconciliationQueue.Chunk chunk) {
                for (NetworkNodeDirectory.Entry entry : directory()
                        .recordsInChunk(
                                chunk.dimension(), new net.minecraft.world.level.ChunkPos(chunk.x(), chunk.z()))) {
                    handleOffer(queue().offerPosition(entry.record().position()));
                }
            }
        }
    }

    /** Releases all lifecycle references on the owning server thread; repeated close is a no-op. */
    @Override
    public void close() {
        MinecraftServer activeServer = server;
        if (activeServer == null) {
            return;
        }
        if (!activeServer.isSameThread()) {
            throw new IllegalStateException("Node authority closed outside the server thread");
        }
        queue().clear();
        startupEntries = List.of();
        startupCursor = 0;
        overflowLogged = false;
        server = null;
        repository = null;
        directory = null;
        nodeIdSource = null;
        workQueue = null;
    }

    private void refreshExactPhysicalSnapshot(NetworkNodeDirectory.Entry entry, PhysicalNode physical) {
        NetworkSavedData network = authoritativeNetwork(entry.networkId());
        NetworkNodeRecord previous = entry.record();
        NetworkNodeRecord updated = network.updateNodePhysicalSnapshot(
                        previous.nodeId(), previous.position(), physical.form(), physical.facing())
                .orElseThrow(() -> new IllegalStateException("Authoritative node changed during reconciliation"));
        if (updated != previous) {
            directory().update(entry, new NetworkNodeDirectory.Entry(entry.networkId(), updated));
        }
    }

    private void replaceWithUnclaimedBlank(ResonanceNodeBlockEntity entity, UUID expectedId) {
        UUID replacement = Objects.requireNonNull(nodeIdSource().get(), "replacement node ID");
        if (replacement.equals(expectedId)
                || directory().byId(replacement).status() != NetworkNodeDirectory.Status.ABSENT) {
            throw new IllegalStateException("Replacement node identity is already claimed");
        }
        entity.replaceWithFreshBlank(expectedId, replacement);
    }

    private void removeEntry(NetworkNodeDirectory.Entry entry) {
        NetworkSavedData network = authoritativeNetwork(entry.networkId());
        NetworkNodeRecord record = entry.record();
        network.removeNode(record.nodeId(), record.position())
                .orElseThrow(() -> new IllegalStateException("Authoritative node changed before removal"));
        directory()
                .remove(entry.networkId(), record.nodeId(), record.position())
                .orElseThrow(() -> new IllegalStateException("Node directory changed before removal"));
    }

    private NetworkSavedData authoritativeNetwork(UUID networkId) {
        return repository()
                .findLoadedNetwork(networkId)
                .orElseThrow(() -> new IllegalStateException("Authoritative network disappeared"));
    }

    private PhysicalNode requirePhysicalNode(ResonanceNodeBlockEntity entity) {
        PhysicalNode physical = physicalNode(entity);
        if (physical == null) {
            throw new IllegalArgumentException("Block entity is not the exact loaded physical node");
        }
        return physical;
    }

    private @Nullable PhysicalNode physicalNode(ResonanceNodeBlockEntity entity) {
        if (!(entity.getLevel() instanceof ServerLevel level) || level.getServer() != server()) {
            return null;
        }
        BlockPos blockPos = entity.getBlockPos();
        if (level.getBlockEntity(blockPos) != entity) {
            return null;
        }
        BlockState state = level.getBlockState(blockPos);
        if (!(state.getBlock() instanceof AbstractResonanceNodeBlock block)) {
            return null;
        }
        return new PhysicalNode(
                GlobalPos.of(level.dimension(), blockPos),
                block.form(),
                state.getValue(AbstractResonanceNodeBlock.FACING));
    }

    private MinecraftServer server() {
        if (server == null) {
            throw new IllegalStateException("Node authority is closed");
        }
        return server;
    }

    private SavedNetworkRepository repository() {
        requireServerThread();
        return Objects.requireNonNull(repository, "Node authority is closed");
    }

    private NetworkNodeDirectory directory() {
        requireServerThread();
        return Objects.requireNonNull(directory, "Node authority is closed");
    }

    private Supplier<UUID> nodeIdSource() {
        requireServerThread();
        return Objects.requireNonNull(nodeIdSource, "Node authority is closed");
    }

    private NodeReconciliationQueue queue() {
        requireServerThread();
        if (workQueue == null) {
            throw new IllegalStateException("Node authority is closed");
        }
        return workQueue;
    }

    private void handleOffer(NodeReconciliationQueue.OfferResult result) {
        if (result == NodeReconciliationQueue.OfferResult.OVERFLOW && !overflowLogged) {
            overflowLogged = true;
            LOGGER.error(
                    "Node reconciliation work reached hard limit {}; new observations wait for another lifecycle event",
                    NodeReconciliationQueue.PRODUCTION_MAXIMUM);
        }
    }

    private void requireServerThread() {
        MinecraftServer activeServer = server();
        if (!activeServer.isSameThread()) {
            throw new IllegalStateException("Node authority accessed outside the server thread");
        }
    }

    private record PhysicalNode(GlobalPos position, NodeForm form, net.minecraft.core.Direction facing) {}
}
