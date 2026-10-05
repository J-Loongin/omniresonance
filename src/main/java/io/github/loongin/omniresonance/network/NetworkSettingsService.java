// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.OwnerSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.security.EditLockTable;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-thread authority for network display settings, owner defaults and empty-network deletion.
 *
 * <p>Every mutating operation revalidates the actual sender, current immutable metadata, revisions and exact edit
 * token. Rename/delete share the network metadata lock with administrator management. No method scans worlds,
 * forces a save, simulates external capabilities or trusts client state. Expected failures occur before authority
 * mutation; physical removal follows the repository's native queued-I/O contract.
 */
public final class NetworkSettingsService implements AutoCloseable {
    public enum Reason {
        NO_ACCESS,
        UNAVAILABLE,
        LOCKED,
        LOCK_EXPIRED,
        STALE_REVISION,
        INVALID_NAME,
        NAME_CONFLICT,
        HAS_NODES,
        STORAGE_UNVERIFIED,
        HAS_EXCHANGES
    }

    /** Stable expected rejection without player-provided text or mutable authority. */
    public static final class Rejected extends IllegalStateException {
        private final Reason reason;

        private Rejected(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** Immutable authorized settings snapshot; owner-only state is absent from administrator views. */
    public record SettingsView(
            NetworkMetadata metadata,
            String ownerName,
            long managementRevision,
            long topologyRevision,
            boolean ownerActions,
            boolean defaultNetwork) {
        public SettingsView {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(ownerName, "ownerName");
            if (ownerName.isEmpty()
                    || ownerName.length() > 256
                    || managementRevision < 0
                    || topologyRevision < 0
                    || (!ownerActions && defaultNetwork)) {
                throw new IllegalArgumentException("Invalid network settings snapshot");
            }
        }
    }

    /** Immutable server-held rename context; possession is not authority and does not bypass live validation. */
    public record RenameEdit(UUID networkId, EditLockTable.Token token, long managementRevision) {
        public RenameEdit {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(token, "token");
            if (managementRevision < 0 || !token.objectId().equals(NetworkManagementLockId.of(networkId))) {
                throw new IllegalArgumentException("Invalid network rename context");
            }
        }
    }

    /** Immutable owner-only deletion summary and exact edit context; it carries no live authority references. */
    public record DeletionEdit(
            UUID networkId,
            EditLockTable.Token token,
            long managementRevision,
            long topologyRevision,
            int administratorCount,
            int tunnelCount,
            int channelCount) {
        public DeletionEdit {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(token, "token");
            if (!token.objectId().equals(NetworkManagementLockId.of(networkId))
                    || managementRevision < 0
                    || topologyRevision < 0
                    || administratorCount < 0
                    || tunnelCount < 0
                    || channelCount < 0) {
                throw new IllegalArgumentException("Invalid network deletion context");
            }
        }
    }

    private final MinecraftServer server;
    private final SavedNetworkRepository repository;
    private final NetworkDirectory directory;
    private final EditLockTable locks;
    private final NetworkAdministrationService.PlayerDirectory players;
    private long currentTick;
    private boolean closed;

    /** Retains existing server-lifecycle collaborators without reading, locking, creating or changing data. */
    public NetworkSettingsService(
            MinecraftServer server,
            SavedNetworkRepository repository,
            NetworkDirectory directory,
            EditLockTable locks,
            NetworkAdministrationService.PlayerDirectory players) {
        this.server = Objects.requireNonNull(server, "server");
        requireServerThread();
        this.repository = Objects.requireNonNull(repository, "repository");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.players = Objects.requireNonNull(players, "players");
    }

    /** Returns only the current actor-authorized settings and does not acquire a lock or mutate state. */
    public SettingsView inspect(ServerPlayer actor, UUID networkId) {
        NetworkSavedData data = requireNetwork(actor, networkId);
        return view(actor, data);
    }

    /** Acquires the shared metadata lock for an owner or administrator at one exact management revision. */
    public RenameEdit beginRename(ServerPlayer actor, UUID networkId) {
        NetworkSavedData data = requireNetwork(actor, networkId);
        return new RenameEdit(networkId, acquire(actor, networkId), data.managementRevision());
    }

    /**
     * Commits one canonical name under the original owner's namespace and always releases this edit. An unchanged
     * canonical name succeeds without revision or dirty-state changes. Invalid drafts and conflicts leave authority
     * untouched; directory preflight precedes the SavedData commit.
     */
    public NetworkMetadata rename(ServerPlayer actor, RenameEdit edit, String name) {
        requireActor(actor);
        Objects.requireNonNull(edit, "edit");
        NetworkSavedData data = requireRename(actor, edit);
        ManagedName managedName;
        try {
            managedName = new ManagedName(name);
        } catch (IllegalArgumentException failure) {
            throw rejected(Reason.INVALID_NAME);
        }
        if (data.metadata().name().equals(managedName)) {
            locks.release(edit.token(), actor.getUUID());
            return data.metadata();
        }
        if (!data.metadata().name().uniquenessKey().equals(managedName.uniquenessKey())
                && directory.containsName(data.metadata().ownerId(), managedName)) {
            throw rejected(Reason.NAME_CONFLICT);
        }
        NetworkSavedData.PreparedRename prepared;
        NetworkDirectory.PreparedRename index;
        try {
            prepared = data.prepareRename(managedName, edit.managementRevision());
            index = directory.prepareRename(prepared.previous(), prepared.next());
        } catch (IllegalArgumentException failure) {
            locks.release(edit.token(), actor.getUUID());
            throw rejected(Reason.STALE_REVISION);
        }
        NetworkMetadata renamed = data.commitRename(prepared);
        directory.commitRename(index);
        repository.auditNetwork(
                renamed.id(),
                io.github.loongin.omniresonance.persistence.AuditEntry.of("rename_network", actor, renamed.id(), ""));
        locks.release(edit.token(), actor.getUUID());
        return renamed;
    }

    /**
     * Sets one current owned network as the actor's default without confirmation. A missing owner shard is created
     * only for this explicit action; repeated selection stays clean and no network revision changes.
     */
    public SettingsView setDefault(ServerPlayer actor, UUID networkId) {
        NetworkSavedData data = requireOwner(actor, networkId);
        try {
            Optional<OwnerSavedData> existing = repository.findOwner(actor.getUUID());
            if (existing.isEmpty()) {
                repository.createOwner(actor.getUUID(), networkId);
            } else {
                existing.orElseThrow().setDefaultNetwork(networkId);
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            if (failure instanceof Rejected rejected) {
                throw rejected;
            }
            throw rejected(Reason.STORAGE_UNVERIFIED);
        }
        return view(actor, data);
    }

    /** Acquires one owner-only deletion edit after proving the current network is empty and storage-free. */
    public DeletionEdit beginDeletion(ServerPlayer actor, UUID networkId) {
        NetworkSavedData data = requireOwner(actor, networkId);
        EditLockTable.Token token = acquire(actor, networkId);
        try {
            requireDeletable(data);
            return new DeletionEdit(
                    networkId,
                    token,
                    data.managementRevision(),
                    data.topologyRevision(),
                    data.administratorCount(),
                    data.tunnelCount(),
                    data.channelCount());
        } catch (RuntimeException failure) {
            locks.release(token, actor.getUUID());
            throw failure;
        }
    }

    /**
     * Revalidates one exact deletion summary, removes only that network and always releases its edit. The owner
     * pointer changes only when it names the deleted network, choosing the earliest other owned network or null.
     */
    public NetworkMetadata delete(ServerPlayer actor, DeletionEdit edit) {
        requireActor(actor);
        Objects.requireNonNull(edit, "edit");
        try {
            NetworkSavedData data = requireDeletion(actor, edit);
            requireDeletable(data);
            if (TopologyDeletionLocks.networkConflict(data, locks, edit.token(), currentTick))
                throw rejected(Reason.LOCKED);
            if (data.administratorCount() != edit.administratorCount()
                    || data.tunnelCount() != edit.tunnelCount()
                    || data.channelCount() != edit.channelCount()) {
                throw rejected(Reason.STALE_REVISION);
            }
            NetworkMetadata removed = data.metadata();
            NetworkDirectory.PreparedRemoval index = directory.prepareRemoval(removed);
            OwnerSavedData ownerData;
            try {
                ownerData = repository.findOwner(removed.ownerId()).orElse(null);
            } catch (IllegalArgumentException | IllegalStateException failure) {
                throw rejected(Reason.STORAGE_UNVERIFIED);
            }
            UUID fallback = directory
                    .firstOwnedExcluding(removed.ownerId(), removed.id())
                    .map(NetworkMetadata::id)
                    .orElse(null);
            if (ownerData == null) ownerData = repository.createOwner(removed.ownerId(), fallback);
            repository.removeNetwork(removed.id());
            directory.commitRemoval(index);
            if (ownerData != null
                    && ownerData.defaultNetworkId().filter(removed.id()::equals).isPresent()) {
                ownerData.setDefaultNetwork(fallback);
            }
            repository.auditOwner(
                    removed.ownerId(),
                    io.github.loongin.omniresonance.persistence.AuditEntry.of(
                            "delete_network", actor, removed.id(), ""));
            return removed;
        } finally {
            locks.release(edit.token(), actor.getUUID());
        }
    }

    /** Renews one exact current edit after repeating its live role, lock and revision checks. */
    public void heartbeat(ServerPlayer actor, RenameEdit edit) {
        requireRename(actor, edit);
        renew(actor, edit.token());
    }

    /** Renews one exact owner deletion edit after repeating its live role, lock and revision checks. */
    public void heartbeat(ServerPlayer actor, DeletionEdit edit) {
        requireDeletion(actor, edit);
        renew(actor, edit.token());
    }

    /** Releases only this actor's exact rename token without changing network state. */
    public void cancel(ServerPlayer actor, RenameEdit edit) {
        requireActor(actor);
        locks.release(Objects.requireNonNull(edit, "edit").token(), actor.getUUID());
    }

    /** Releases only this actor's exact deletion token without changing network state. */
    public void cancel(ServerPlayer actor, DeletionEdit edit) {
        requireActor(actor);
        locks.release(Objects.requireNonNull(edit, "edit").token(), actor.getUUID());
    }

    /** Advances the service clock once per server tick; lock expiry remains owned by the shared table lifecycle. */
    public void tick() {
        requireOpen();
        currentTick = Math.incrementExact(currentTick);
    }

    /** Ends this service without clearing shared locks; session owners release exact edits before shutdown. */
    @Override
    public void close() {
        requireServerThread();
        closed = true;
    }

    private SettingsView view(ServerPlayer actor, NetworkSavedData data) {
        NetworkMetadata metadata = data.metadata();
        boolean owner = actor.getUUID().equals(metadata.ownerId());
        boolean isDefault = false;
        if (owner) {
            try {
                isDefault = repository
                        .findOwner(metadata.ownerId())
                        .flatMap(OwnerSavedData::defaultNetworkId)
                        .filter(metadata.id()::equals)
                        .isPresent();
            } catch (IllegalArgumentException | IllegalStateException failure) {
                throw rejected(Reason.STORAGE_UNVERIFIED);
            }
        }
        return new SettingsView(
                metadata,
                players.knownName(metadata.ownerId()),
                data.managementRevision(),
                data.topologyRevision(),
                owner,
                isDefault);
    }

    private NetworkSavedData requireRename(ServerPlayer actor, RenameEdit edit) {
        Objects.requireNonNull(edit, "edit");
        NetworkSavedData data;
        try {
            data = requireNetwork(actor, edit.networkId());
        } catch (Rejected rejected) {
            locks.release(edit.token(), actor.getUUID());
            throw rejected;
        }
        requireHeld(actor, edit.token());
        if (data.managementRevision() != edit.managementRevision()) {
            locks.release(edit.token(), actor.getUUID());
            throw rejected(Reason.STALE_REVISION);
        }
        return data;
    }

    private NetworkSavedData requireDeletion(ServerPlayer actor, DeletionEdit edit) {
        Objects.requireNonNull(edit, "edit");
        NetworkSavedData data = requireOwner(actor, edit.networkId());
        requireHeld(actor, edit.token());
        if (data.managementRevision() != edit.managementRevision()
                || data.topologyRevision() != edit.topologyRevision()) {
            throw rejected(Reason.STALE_REVISION);
        }
        return data;
    }

    private void requireDeletable(NetworkSavedData data) {
        if (data.nodeCount() != 0) {
            throw rejected(Reason.HAS_NODES);
        }
        if (data.recovery().hasActiveReservations()
                || !data.recovery().isEmpty()
                || data.directBindingCount() != 0
                || data.domainConfigurationCount() != 0) {
            throw rejected(Reason.STORAGE_UNVERIFIED);
        }
        try {
            if (repository.hasUnresolvedExchanges(data.metadata().id())) throw rejected(Reason.HAS_EXCHANGES);
            repository.requireEmptyNetworkStorage(data.metadata().id());
        } catch (IllegalArgumentException | IllegalStateException failure) {
            if (failure instanceof Rejected rejected) throw rejected;
            throw rejected(Reason.STORAGE_UNVERIFIED);
        }
    }

    private NetworkSavedData requireNetwork(ServerPlayer actor, UUID networkId) {
        requireActor(actor);
        Objects.requireNonNull(networkId, "networkId");
        NetworkMetadata indexed = directory.find(networkId).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        NetworkSavedData data = repository.findLoadedNetwork(networkId).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        if (!data.metadata().equals(indexed)) {
            throw rejected(Reason.UNAVAILABLE);
        }
        if (!actor.getUUID().equals(indexed.ownerId())
                && !indexed.administrators().contains(actor.getUUID())) {
            throw rejected(Reason.NO_ACCESS);
        }
        return data;
    }

    private NetworkSavedData requireOwner(ServerPlayer actor, UUID networkId) {
        NetworkSavedData data = requireNetwork(actor, networkId);
        if (!data.metadata().ownerId().equals(actor.getUUID())) {
            throw rejected(Reason.NO_ACCESS);
        }
        return data;
    }

    private EditLockTable.Token acquire(ServerPlayer actor, UUID networkId) {
        return locks.tryAcquire(NetworkManagementLockId.of(networkId), actor.getUUID(), currentTick)
                .orElseThrow(() -> rejected(Reason.LOCKED));
    }

    private void requireHeld(ServerPlayer actor, EditLockTable.Token token) {
        if (!locks.isHeld(token, actor.getUUID(), currentTick)) {
            throw rejected(Reason.LOCK_EXPIRED);
        }
    }

    private void renew(ServerPlayer actor, EditLockTable.Token token) {
        if (!locks.renew(token, actor.getUUID(), currentTick)) {
            throw rejected(Reason.LOCK_EXPIRED);
        }
    }

    private void requireActor(ServerPlayer actor) {
        requireOpen();
        if (Objects.requireNonNull(actor, "actor").server != server) {
            throw new IllegalArgumentException("Foreign server actor");
        }
    }

    private void requireOpen() {
        requireServerThread();
        if (closed) {
            throw rejected(Reason.UNAVAILABLE);
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Network settings accessed outside server thread");
        }
    }

    private static Rejected rejected(Reason reason) {
        return new Rejected(reason);
    }
}
