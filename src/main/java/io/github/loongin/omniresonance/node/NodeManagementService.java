// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.security.EditLockTable;
import io.github.loongin.omniresonance.security.NetworkPermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread authority for real-player node editing, revision checks and one-session edit leases.
 *
 * <p>The service accepts actual {@link ServerPlayer} instances and derives every actor UUID, role, network relation,
 * record and physical node from current server authority. It never trusts an owner claim, loads a chunk, scans the
 * world, simulates transfer, grants a chunk ticket, saves synchronously or performs asynchronous work. Successful
 * commits update SavedData before the deterministic derived directory and release the exact lease. Expected
 * rejections do not partially mutate authority.
 *
 * <p>Blank edit context is keyed by node UUID and contains only immutable player/network/position/token values. Its
 * capacity cannot exceed active edit leases; exact cancel, save, logout, expiry and close remove it. No player, level,
 * chunk or block entity is retained between calls.
 */
public final class NodeManagementService implements AutoCloseable {
    /** Stable internal rejection categories for future bounded protocol mapping. */
    public enum Reason {
        NO_ACCESS,
        UNAVAILABLE,
        OUT_OF_RANGE,
        LOCKED,
        LOCK_EXPIRED,
        STALE_REVISION,
        NAME_CONFLICT,
        NODE_DISABLED,
        RESET_REQUIRED
    }

    /** Expected request rejection carrying no player-visible text or mutable state. */
    public static final class Rejected extends IllegalStateException {
        private final Reason reason;

        private Rejected(Reason reason) {
            super(Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        /** Returns the stable internal reason without mutation, simulation or authority access. */
        public Reason reason() {
            return reason;
        }
    }

    /** Immutable linked-edit snapshot; saving must still revalidate its token, role, existence and revision. */
    public record LinkedEdit(EditLockTable.Token token, UUID networkId, NetworkNodeRecord node) {
        public LinkedEdit {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(node, "node");
            if (!token.objectId().equals(node.nodeId())) {
                throw new IllegalArgumentException("Edit token does not identify its node snapshot");
            }
        }
    }

    /** Immutable two-network move edit; commit still revalidates both roles, physical state, token and revision. */
    public record NetworkMoveEdit(
            EditLockTable.Token token, UUID sourceNetworkId, UUID targetNetworkId, NetworkNodeRecord node) {
        public NetworkMoveEdit {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(sourceNetworkId, "sourceNetworkId");
            Objects.requireNonNull(targetNetworkId, "targetNetworkId");
            Objects.requireNonNull(node, "node");
            if (sourceNetworkId.equals(targetNetworkId) || !token.objectId().equals(node.nodeId())) {
                throw new IllegalArgumentException("Invalid node network-move edit");
            }
        }
    }

    /** Immutable result of one physical node inspection; no world object or mutable collection escapes. */
    public sealed interface PhysicalAccess permits BlankAccess, LinkedAccess {}

    /** Exact reconciled blank physical state without a network or private configuration. */
    public record BlankAccess(UUID nodeId, GlobalPos position, NodeForm form, Direction facing)
            implements PhysicalAccess {
        public BlankAccess {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(form, "form");
            Objects.requireNonNull(facing, "facing");
        }
    }

    /** Exact authorized linked state containing immutable network metadata and node authority. */
    public record LinkedAccess(NetworkMetadata network, NetworkNodeRecord node) implements PhysicalAccess {
        public LinkedAccess {
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(node, "node");
        }
    }

    private @Nullable MinecraftServer server;
    private @Nullable NetworkDirectory networks;
    private @Nullable SavedNetworkRepository repository;
    private @Nullable NetworkNodeDirectory nodes;
    private @Nullable NodeAuthorityService authority;
    private @Nullable EditLockTable locks;
    private final Map<UUID, BlankEdit> blankEdits = new HashMap<>();
    private long currentTick;

    /**
     * Retains one matching server lifecycle and its already-composed authority collaborators.
     * Construction and all subsequent access must run on the server thread; no data is loaded or created here.
     */
    public NodeManagementService(
            MinecraftServer server,
            NetworkDirectory networks,
            SavedNetworkRepository repository,
            NetworkNodeDirectory nodes,
            NodeAuthorityService authority,
            EditLockTable locks) {
        this.server = Objects.requireNonNull(server, "server");
        requireServerThread();
        this.networks = Objects.requireNonNull(networks, "networks");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    /**
     * Reconciles and inspects one exact nearby loaded physical node for Menu opening.
     *
     * <p>Blank results expose only physical identity. Linked results require the actor's current owner/administrator
     * role and return current immutable authority. This server-thread query never loads a chunk, acquires a lock,
     * saves synchronously or retains the block entity after return; B2 reconciliation may update existing authority.
     */
    public PhysicalAccess inspectPhysical(ServerPlayer actor, BlockPos position) {
        requireActor(actor);
        Objects.requireNonNull(position, "position");
        PhysicalNode physical = requirePhysicalNode(actor, position, true);
        if (physical.state().linkState() == NodeLinkState.BLANK) {
            NetworkNodeDirectory activeNodes = nodes();
            if (activeNodes.byId(physical.state().nodeId()).status() != NetworkNodeDirectory.Status.ABSENT
                    || activeNodes.byPosition(physical.position()).status() != NetworkNodeDirectory.Status.ABSENT) {
                throw rejected(Reason.UNAVAILABLE);
            }
            return new BlankAccess(physical.state().nodeId(), physical.position(), physical.form(), physical.facing());
        }
        NetworkNodeDirectory.Entry entry = uniqueEntry(physical.state().nodeId());
        if (!entry.record().position().equals(physical.position())) {
            throw rejected(Reason.UNAVAILABLE);
        }
        NetworkSavedData network = requireNetwork(actor, entry.networkId());
        LinkedTarget target =
                requireLinkedTarget(entry.networkId(), physical.state().nodeId(), network);
        return new LinkedAccess(network.metadata(), target.entry().record());
    }

    /**
     * Reads one exact linked node for an authorized actor without world access, locking or mutation.
     * Missing, conflicting, stale-directory or unauthorized authority rejects fail-closed.
     */
    public NetworkNodeRecord inspectLinked(ServerPlayer actor, UUID networkId, UUID nodeId) {
        requireActor(actor);
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(nodeId, "nodeId");
        NetworkSavedData network = requireNetwork(actor, networkId);
        return requireLinkedTarget(networkId, nodeId, network).entry().record();
    }

    /**
     * Returns the next positive node-number preview for one currently managed healthy network.
     * This server-thread query performs no reservation, mutation, dirtying, locking, I/O or world access; overflow
     * rejects before producing a value.
     */
    public long suggestedNodeNumber(ServerPlayer actor, UUID networkId) {
        requireActor(actor);
        Objects.requireNonNull(networkId, "networkId");
        return Math.incrementExact(requireNetwork(actor, networkId).lastNodeNumber());
    }

    /**
     * Pure O(1) Menu-validity check for one already-open physical node.
     *
     * <p>This method never reconciles, mutates, dirties, renews a lock, loads a chunk or sends a packet. Ordinary
     * distance, block, identity, role and authority loss returns false. Null inputs and wrong-thread access reject.
     */
    public boolean canKeepPhysicalMenuOpen(
            ServerPlayer actor, BlockPos position, UUID expectedNodeId, @Nullable UUID linkedNetworkId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(expectedNodeId, "expectedNodeId");
        if (server == null) {
            return false;
        }
        requireServerThread();
        if (actor.server != server) {
            return false;
        }
        PhysicalNode physical = physicalNode(actor, position);
        if (physical == null || !physical.state().nodeId().equals(expectedNodeId)) {
            return false;
        }
        if (linkedNetworkId == null) {
            if (physical.state().linkState() == NodeLinkState.LINKED) {
                return true;
            }
            return nodes().byId(expectedNodeId).status() == NetworkNodeDirectory.Status.ABSENT
                    && nodes().byPosition(physical.position()).status() == NetworkNodeDirectory.Status.ABSENT;
        }
        if (physical.state().linkState() != NodeLinkState.LINKED) {
            return false;
        }
        try {
            NetworkSavedData network = requireNetwork(actor, linkedNetworkId);
            LinkedTarget target = requireLinkedTarget(linkedNetworkId, expectedNodeId, network);
            return target.entry().record().position().equals(physical.position());
        } catch (Rejected unavailable) {
            return false;
        }
    }

    /**
     * Acquires one nearby exact BLANK node for an actor currently authorized on the selected network.
     * The method may perform synchronous B2 reconciliation but never loads a chunk or links the node. A live lease
     * rejects as LOCKED; every failure occurs before adding blank context.
     */
    public EditLockTable.Token acquireBlank(ServerPlayer actor, BlockPos position, UUID networkId) {
        requireActor(actor);
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(networkId, "networkId");
        requireNetwork(actor, networkId);
        PhysicalBlank physical = requirePhysicalBlank(actor, position);
        NetworkNodeDirectory activeNodes = nodes();
        if (activeNodes.byId(physical.nodeId()).status() != NetworkNodeDirectory.Status.ABSENT
                || activeNodes.byPosition(physical.position()).status() != NetworkNodeDirectory.Status.ABSENT) {
            throw rejected(Reason.UNAVAILABLE);
        }
        EditLockTable.Token token = locks().tryAcquire(physical.nodeId(), actor.getUUID(), currentTick)
                .orElseThrow(() -> rejected(Reason.LOCKED));
        blankEdits.put(physical.nodeId(), new BlankEdit(actor.getUUID(), networkId, physical.position(), token));
        return token;
    }

    /**
     * Acquires one healthy linked node for an owner/administrator without loading its physical chunk.
     * The returned immutable snapshot is advisory; every save revalidates exact current authority and revision.
     */
    public LinkedEdit acquireLinked(ServerPlayer actor, UUID networkId, UUID nodeId) {
        requireActor(actor);
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(nodeId, "nodeId");
        NetworkSavedData network = requireNetwork(actor, networkId);
        LinkedTarget target = requireLinkedTarget(networkId, nodeId, network);
        EditLockTable.Token token =
                locks().tryAcquire(nodeId, actor.getUUID(), currentTick).orElseThrow(() -> rejected(Reason.LOCKED));
        return new LinkedEdit(token, networkId, target.entry().record());
    }

    /** Acquires one enabled linked node after checking current management access to both distinct networks. */
    public NetworkMoveEdit beginNetworkMove(
            ServerPlayer actor, UUID sourceNetworkId, UUID nodeId, UUID targetNetworkId) {
        requireActor(actor);
        Objects.requireNonNull(sourceNetworkId, "sourceNetworkId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(targetNetworkId, "targetNetworkId");
        if (sourceNetworkId.equals(targetNetworkId)) {
            throw rejected(Reason.UNAVAILABLE);
        }
        requireNetwork(actor, targetNetworkId);
        LinkedEdit linked = acquireLinked(actor, sourceNetworkId, nodeId);
        if (!linked.node().enabled()) {
            releaseExact(linked.token(), actor.getUUID());
            throw rejected(Reason.NODE_DISABLED);
        }
        return new NetworkMoveEdit(linked.token(), sourceNetworkId, targetNetworkId, linked.node());
    }

    /**
     * Commits one preauthorized nearby node move. Target-name conflict retains the edit for correction; all other
     * authority failures release or invalidate the edit through the normal node lease lifecycle.
     */
    public NetworkNodeRecord moveNetwork(ServerPlayer actor, NetworkMoveEdit edit, ManagedName targetName) {
        requireActor(actor);
        Objects.requireNonNull(edit, "edit");
        Objects.requireNonNull(targetName, "targetName");
        requireHeld(actor, edit.token());
        NetworkSavedData target = requireNetwork(actor, edit.targetNetworkId());
        if (target.containsNodeName(targetName)) {
            throw rejected(Reason.NAME_CONFLICT);
        }
        NetworkNodeRecord advisory = edit.node();
        if (!advisory.position().dimension().equals(actor.serverLevel().dimension())) {
            releaseExact(edit.token(), actor.getUUID());
            throw rejected(Reason.UNAVAILABLE);
        }
        PhysicalNode physical = requirePhysicalNode(actor, advisory.position().pos(), false);
        if (!physical.state().nodeId().equals(advisory.nodeId())
                || physical.state().linkState() != NodeLinkState.LINKED) {
            releaseExact(edit.token(), actor.getUUID());
            throw rejected(Reason.UNAVAILABLE);
        }
        LinkedTarget source = requireLinkedSave(actor, edit.sourceNetworkId(), advisory.revision(), edit.token());
        requireNetwork(actor, edit.targetNetworkId());
        try {
            NetworkNodeRecord moved = authority()
                    .moveNetwork(
                            edit.sourceNetworkId(),
                            edit.targetNetworkId(),
                            physical.entity(),
                            source.entry().record().revision(),
                            targetName);
            releaseExact(edit.token(), actor.getUUID());
            return moved;
        } catch (IllegalArgumentException targetRejected) {
            if (target.containsNodeName(targetName)) {
                throw rejected(Reason.NAME_CONFLICT);
            }
            releaseExact(edit.token(), actor.getUUID());
            throw rejected(Reason.UNAVAILABLE);
        } catch (IllegalStateException stale) {
            releaseExact(edit.token(), actor.getUUID());
            throw rejected(Reason.STALE_REVISION);
        }
    }

    /**
     * Renews one exact live lease after rechecking actor, network role and blank/linked object existence.
     * Losing permission, identity or authority releases the lease and rejects. No revision or persistent state changes.
     */
    public void heartbeat(ServerPlayer actor, EditLockTable.Token token) {
        requireActor(actor);
        Objects.requireNonNull(token, "token");
        requireHeld(actor, token);
        BlankEdit blank = matchingBlankEdit(token);
        try {
            if (blank != null) {
                requireNetwork(actor, blank.networkId());
                PhysicalBlank physical =
                        requirePhysicalBlank(actor, blank.position().pos());
                if (!physical.nodeId().equals(token.objectId())
                        || !physical.position().equals(blank.position())) {
                    throw rejected(Reason.UNAVAILABLE);
                }
            } else {
                NetworkNodeDirectory.Entry entry = uniqueEntry(token.objectId());
                NetworkSavedData network = requireNetwork(actor, entry.networkId());
                requireLinkedTarget(entry.networkId(), token.objectId(), network);
            }
        } catch (Rejected failure) {
            if (failure.reason() == Reason.NO_ACCESS
                    || failure.reason() == Reason.UNAVAILABLE
                    || failure.reason() == Reason.OUT_OF_RANGE) {
                releaseExact(token, actor.getUUID());
            }
            throw failure;
        }
        if (!locks().renew(token, actor.getUUID(), currentTick)) {
            removeBlankContext(token);
            throw rejected(Reason.LOCK_EXPIRED);
        }
    }

    /**
     * Commits one exact nearby blank node into the selected healthy network after full role/lease/name revalidation.
     * A name conflict retains the lease for a corrected retry; success releases it. No player-visible session is kept.
     */
    public NetworkNodeRecord linkBlank(
            ServerPlayer actor, BlockPos position, UUID networkId, ManagedName name, EditLockTable.Token token) {
        requireActor(actor);
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(token, "token");
        requireHeld(actor, token);
        BlankEdit blank = matchingBlankEdit(token);
        if (blank == null || !blank.playerId().equals(actor.getUUID())) {
            throw rejected(Reason.LOCK_EXPIRED);
        }
        if (!blank.networkId().equals(networkId)
                || !blank.position().equals(GlobalPos.of(actor.serverLevel().dimension(), position))) {
            releaseExact(token, actor.getUUID());
            throw rejected(Reason.UNAVAILABLE);
        }
        NetworkSavedData network;
        PhysicalBlank physical;
        try {
            network = requireNetwork(actor, networkId);
            physical = requirePhysicalBlank(actor, position);
            if (!physical.nodeId().equals(token.objectId())
                    || !physical.position().equals(blank.position())) {
                throw rejected(Reason.UNAVAILABLE);
            }
            NetworkNodeDirectory activeNodes = nodes();
            if (activeNodes.byId(token.objectId()).status() != NetworkNodeDirectory.Status.ABSENT
                    || activeNodes.byPosition(physical.position()).status() != NetworkNodeDirectory.Status.ABSENT) {
                throw rejected(Reason.UNAVAILABLE);
            }
        } catch (Rejected failure) {
            releaseExact(token, actor.getUUID());
            throw failure;
        }
        if (network.containsNodeName(name)) {
            throw rejected(Reason.NAME_CONFLICT);
        }
        NetworkNodeRecord linked = authority().link(networkId, physical.entity(), name);
        releaseExact(token, actor.getUUID());
        return linked;
    }

    /** Renames one enabled exact-revision node and releases its lease after the committed directory replacement. */
    public NetworkNodeRecord rename(
            ServerPlayer actor, UUID networkId, long revision, ManagedName name, EditLockTable.Token token) {
        Objects.requireNonNull(name, "name");
        LinkedTarget target = requireLinkedSave(actor, networkId, revision, token);
        if (!target.entry().record().enabled()) {
            throw rejected(Reason.NODE_DISABLED);
        }
        Optional<NetworkNodeRecord> updated;
        try {
            updated = target.network().renameNode(token.objectId(), revision, name);
        } catch (IllegalArgumentException conflict) {
            throw rejected(Reason.NAME_CONFLICT);
        }
        return commitLinked(actor, token, target, updated);
    }

    /** Changes the only node enabled state for an exact revision and releases the lease after commit. */
    public NetworkNodeRecord setEnabled(
            ServerPlayer actor, UUID networkId, long revision, boolean enabled, EditLockTable.Token token) {
        LinkedTarget target = requireLinkedSave(actor, networkId, revision, token);
        return commitLinked(actor, token, target, target.network().setNodeEnabled(token.objectId(), revision, enabled));
    }

    /** Changes only the persisted request of an enabled exact-revision node; no chunk ticket is granted. */
    public NetworkNodeRecord setChunkLoadingRequested(
            ServerPlayer actor, UUID networkId, long revision, boolean requested, EditLockTable.Token token) {
        LinkedTarget target = requireLinkedSave(actor, networkId, revision, token);
        if (!target.entry().record().enabled()) {
            throw rejected(Reason.NODE_DISABLED);
        }
        return commitLinked(
                actor,
                token,
                target,
                target.network().setNodeChunkLoadingRequested(token.objectId(), revision, requested));
    }

    /** Changes an enabled node mode after reset intent validation; no tunnel/domain configuration is created. */
    public NetworkNodeRecord setMode(
            ServerPlayer actor,
            UUID networkId,
            long revision,
            NodeMode mode,
            boolean confirmedReset,
            EditLockTable.Token token) {
        Objects.requireNonNull(mode, "mode");
        if (mode == NodeMode.UNCONFIGURED) {
            throw new IllegalArgumentException("Unconfigured mode cannot be selected");
        }
        LinkedTarget target = requireLinkedSave(actor, networkId, revision, token);
        NetworkNodeRecord current = target.entry().record();
        if (!current.enabled()) {
            throw rejected(Reason.NODE_DISABLED);
        }
        if (current.mode() != NodeMode.UNCONFIGURED && current.mode() != mode && !confirmedReset) {
            throw rejected(Reason.RESET_REQUIRED);
        }
        return commitLinked(
                actor, token, target, target.network().setNodeMode(token.objectId(), revision, mode, confirmedReset));
    }

    /** Releases one exact actor-owned lease for cancel/close without persistent or world mutation. */
    public void cancel(ServerPlayer actor, EditLockTable.Token token) {
        requireActor(actor);
        Objects.requireNonNull(token, "token");
        if (!releaseExact(token, actor.getUUID())) {
            throw rejected(Reason.LOCK_EXPIRED);
        }
    }

    /** Releases all leases and immutable blank context for one disconnected/replaced player UUID. */
    public void releasePlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (server == null) {
            return;
        }
        requireServerThread();
        blankEdits.values().removeIf(edit -> edit.playerId().equals(playerId));
        locks().releasePlayer(playerId);
    }

    /** Advances one lifecycle gt and removes only leases due at this exact monotonic tick. */
    public void tick() {
        requireOpen();
        requireServerThread();
        long nextTick = Math.incrementExact(currentTick);
        EditLockTable.Token expired;
        while ((expired = locks().pollExpired(nextTick).orElse(null)) != null) {
            removeBlankContext(expired);
        }
        currentTick = nextTick;
    }

    /** Clears every lease/context and releases lifecycle collaborators; repeated close is a no-op. */
    @Override
    public void close() {
        MinecraftServer activeServer = server;
        if (activeServer == null) {
            return;
        }
        if (!activeServer.isSameThread()) {
            throw new IllegalStateException("Node management closed outside the server thread");
        }
        locks().clear();
        blankEdits.clear();
        server = null;
        networks = null;
        repository = null;
        nodes = null;
        authority = null;
        locks = null;
    }

    private LinkedTarget requireLinkedSave(
            ServerPlayer actor, UUID networkId, long revision, EditLockTable.Token token) {
        requireActor(actor);
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(token, "token");
        if (revision < 0) {
            throw new IllegalArgumentException("Expected node revision must be nonnegative");
        }
        requireHeld(actor, token);
        try {
            NetworkSavedData network = requireNetwork(actor, networkId);
            LinkedTarget target = requireLinkedTarget(networkId, token.objectId(), network);
            if (target.entry().record().revision() != revision) {
                throw rejected(Reason.STALE_REVISION);
            }
            return target;
        } catch (Rejected failure) {
            if (failure.reason() == Reason.NO_ACCESS || failure.reason() == Reason.UNAVAILABLE) {
                releaseExact(token, actor.getUUID());
            }
            throw failure;
        }
    }

    private NetworkNodeRecord commitLinked(
            ServerPlayer actor,
            EditLockTable.Token token,
            LinkedTarget target,
            Optional<NetworkNodeRecord> updatedResult) {
        NetworkNodeRecord updated = updatedResult.orElseThrow(() -> rejected(Reason.STALE_REVISION));
        NetworkNodeDirectory.Entry updatedEntry =
                new NetworkNodeDirectory.Entry(target.entry().networkId(), updated);
        nodes().update(target.entry(), updatedEntry);
        releaseExact(token, actor.getUUID());
        return updated;
    }

    private NetworkSavedData requireNetwork(ServerPlayer actor, UUID networkId) {
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

    private LinkedTarget requireLinkedTarget(UUID networkId, UUID nodeId, NetworkSavedData network) {
        NetworkNodeDirectory.Entry entry = uniqueEntry(nodeId);
        if (!entry.networkId().equals(networkId)) {
            throw rejected(Reason.UNAVAILABLE);
        }
        NetworkNodeRecord authoritative = network.findNode(nodeId).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        if (!authoritative.equals(entry.record())) {
            throw rejected(Reason.UNAVAILABLE);
        }
        return new LinkedTarget(network, entry);
    }

    private NetworkNodeDirectory.Entry uniqueEntry(UUID nodeId) {
        NetworkNodeDirectory.Lookup lookup = nodes().byId(nodeId);
        if (lookup.status() != NetworkNodeDirectory.Status.UNIQUE) {
            throw rejected(Reason.UNAVAILABLE);
        }
        return lookup.entry().orElseThrow();
    }

    private PhysicalBlank requirePhysicalBlank(ServerPlayer actor, BlockPos position) {
        PhysicalNode physical = requirePhysicalNode(actor, position, true);
        if (physical.state().linkState() != NodeLinkState.BLANK) {
            throw rejected(Reason.UNAVAILABLE);
        }
        return new PhysicalBlank(physical.position(), physical.state().nodeId(), physical.entity());
    }

    private PhysicalNode requirePhysicalNode(ServerPlayer actor, BlockPos position, boolean reconcile) {
        PhysicalNode physical = physicalNode(actor, position);
        if (physical == null) {
            if (actor.distanceToSqr(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5) > 64.0) {
                throw rejected(Reason.OUT_OF_RANGE);
            }
            throw rejected(Reason.UNAVAILABLE);
        }
        if (reconcile) {
            authority().reconcileLoaded(physical.entity());
            NodePersistentState.Valid state = physical.entity().state().orElseThrow(() -> rejected(Reason.UNAVAILABLE));
            return new PhysicalNode(physical.position(), state, physical.form(), physical.facing(), physical.entity());
        }
        return physical;
    }

    private @Nullable PhysicalNode physicalNode(ServerPlayer actor, BlockPos position) {
        if (actor.distanceToSqr(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5) > 64.0) {
            return null;
        }
        ServerLevel level = actor.serverLevel();
        LevelChunk chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
        if (chunk == null) {
            return null;
        }
        BlockState state = chunk.getBlockState(position);
        BlockEntity blockEntity = chunk.getBlockEntity(position);
        if (!(state.getBlock() instanceof AbstractResonanceNodeBlock block)
                || !(blockEntity instanceof ResonanceNodeBlockEntity node)) {
            return null;
        }
        NodePersistentState.Valid persistent = node.state().orElse(null);
        if (persistent == null) {
            return null;
        }
        return new PhysicalNode(
                GlobalPos.of(level.dimension(), position),
                persistent,
                block.form(),
                state.getValue(AbstractResonanceNodeBlock.FACING),
                node);
    }

    private void requireHeld(ServerPlayer actor, EditLockTable.Token token) {
        if (!locks().isHeld(token, actor.getUUID(), currentTick)) {
            throw rejected(Reason.LOCK_EXPIRED);
        }
    }

    private boolean releaseExact(EditLockTable.Token token, UUID playerId) {
        boolean released = locks().release(token, playerId);
        if (released) {
            removeBlankContext(token);
        }
        return released;
    }

    private @Nullable BlankEdit matchingBlankEdit(EditLockTable.Token token) {
        BlankEdit blank = blankEdits.get(token.objectId());
        return blank != null && blank.token().equals(token) ? blank : null;
    }

    private void removeBlankContext(EditLockTable.Token token) {
        BlankEdit blank = blankEdits.get(token.objectId());
        if (blank != null && blank.token().equals(token)) {
            blankEdits.remove(token.objectId());
        }
    }

    private void requireActor(ServerPlayer actor) {
        Objects.requireNonNull(actor, "actor");
        requireOpen();
        requireServerThread();
        if (actor.server != server) {
            throw rejected(Reason.UNAVAILABLE);
        }
    }

    private void requireOpen() {
        if (server == null) {
            throw rejected(Reason.UNAVAILABLE);
        }
    }

    private void requireServerThread() {
        MinecraftServer activeServer = server;
        if (activeServer == null) {
            throw rejected(Reason.UNAVAILABLE);
        }
        if (!activeServer.isSameThread()) {
            throw new IllegalStateException("Node management accessed outside the server thread");
        }
    }

    private NetworkDirectory networks() {
        requireServerThread();
        return Objects.requireNonNull(networks, "Node management is closed");
    }

    private SavedNetworkRepository repository() {
        requireServerThread();
        return Objects.requireNonNull(repository, "Node management is closed");
    }

    private NetworkNodeDirectory nodes() {
        requireServerThread();
        return Objects.requireNonNull(nodes, "Node management is closed");
    }

    private NodeAuthorityService authority() {
        requireServerThread();
        return Objects.requireNonNull(authority, "Node management is closed");
    }

    private EditLockTable locks() {
        requireServerThread();
        return Objects.requireNonNull(locks, "Node management is closed");
    }

    private static Rejected rejected(Reason reason) {
        return new Rejected(reason);
    }

    private record BlankEdit(UUID playerId, UUID networkId, GlobalPos position, EditLockTable.Token token) {
        private BlankEdit {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(token, "token");
        }
    }

    private record LinkedTarget(NetworkSavedData network, NetworkNodeDirectory.Entry entry) {}

    private record PhysicalBlank(GlobalPos position, UUID nodeId, ResonanceNodeBlockEntity entity) {}

    private record PhysicalNode(
            GlobalPos position,
            NodePersistentState.Valid state,
            NodeForm form,
            Direction facing,
            ResonanceNodeBlockEntity entity) {}
}
