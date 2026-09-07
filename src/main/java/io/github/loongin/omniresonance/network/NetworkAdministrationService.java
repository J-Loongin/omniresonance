// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.config.ServerConfig;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.networking.NetworkMemberPage;
import io.github.loongin.omniresonance.networking.NetworkMemberSummary;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.security.EditLockTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread membership authority sharing the existing directory, SavedData and edit locks. No simulation,
 * external lookup, synchronous save, client state or world scan is used. Callers own removal edit lifetimes and
 * send scoped revocation notices after a successful removal; authorization changes before those notices.
 */
public final class NetworkAdministrationService implements AutoCloseable {
    public static final int MAXIMUM_CANDIDATES = 262144;

    public enum Reason {
        NO_ACCESS,
        UNAVAILABLE,
        LOCKED,
        LOCK_EXPIRED,
        STALE_REVISION,
        PLAYER_OFFLINE,
        ALREADY_ADMINISTRATOR,
        NOT_ADMINISTRATOR,
        OWNER_TARGET,
        QUOTA_REACHED
    }

    /** Stable internal rejection; never contains a user-provided message or mutable authority. */
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

    /** Immutable bounded profile identity, safe to share; construction performs no lookup or authorization. */
    public record PlayerIdentity(UUID id, String name) {
        public PlayerIdentity {
            Objects.requireNonNull(id, "id");
            if (!new ManagedName(name).value().equals(name))
                throw new IllegalArgumentException("Noncanonical player name");
        }
    }

    /** Server-thread profile adapter. Implementations must not access external services or create player identities. */
    public interface PlayerDirectory {
        /** Returns only a currently connected identity; absence never triggers a lookup or mutation. */
        Optional<PlayerIdentity> online(UUID id);
        /** Returns an independently owned snapshot and rejects excess before copying more than maximum entries. */
        List<PlayerIdentity> snapshotOnline(int maximum);
        /** Returns local cached display text or the UUID; names are never authorization credentials. */
        String knownName(UUID id);
    }

    /** Immutable server-held removal context; tokens remain subject to live ownership and revision checks. */
    public record RemovalEdit(UUID networkId, UUID target, EditLockTable.Token token, long revision) {
        public RemovalEdit {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(token, "token");
            if (revision < 0 || !token.objectId().equals(NetworkManagementLockId.of(networkId))) {
                throw new IllegalArgumentException("Invalid member removal context");
            }
        }
    }

    private final MinecraftServer server;
    private final SavedNetworkRepository repository;
    private final NetworkDirectory directory;
    private final EditLockTable locks;
    private final PlayerDirectory players;
    private final long configurationEpoch;
    private final boolean configurationLoaded;
    private long configurationRevision;
    private ServerSettings settings;
    private long currentTick;
    private boolean closed;

    /** Retains caller-owned lifecycle collaborators without creating members, locks, files or implicit networks. */
    public NetworkAdministrationService(
            MinecraftServer server,
            SavedNetworkRepository repository,
            NetworkDirectory directory,
            EditLockTable locks,
            PlayerDirectory players,
            ServerConfig.State initial) {
        this.server = Objects.requireNonNull(server, "server");
        requireServerThread();
        this.repository = Objects.requireNonNull(repository, "repository");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.players = Objects.requireNonNull(players, "players");
        Objects.requireNonNull(initial, "initial");
        configurationEpoch = initial.epoch();
        configurationLoaded = initial.loaded();
        configurationRevision = initial.revision();
        settings =
                initial.loaded() ? Objects.requireNonNull(initial.settings(), "settings") : ServerSettings.defaults();
    }

    /** Reads immutable metadata for a currently authorized actor; no lock, mutation or lookup side effect. */
    public NetworkMetadata inspectNetwork(ServerPlayer actor, UUID networkId) {
        return requireNetwork(actor, networkId).metadata();
    }

    /** Reads owner-only metadata without acquiring a lease; used to validate candidate-page continuation. */
    public NetworkMetadata inspectOwner(ServerPlayer actor, UUID networkId) {
        return requireOwner(actor, networkId).metadata();
    }

    /** Reads one authorized member's local display identity, without a lookup outside the server. */
    public NetworkMemberSummary member(ServerPlayer actor, UUID networkId, UUID target) {
        NetworkSavedData data = requireNetwork(actor, networkId);
        if (!data.metadata().ownerId().equals(target)
                && !data.metadata().administrators().contains(target)) {
            throw rejected(Reason.NOT_ADMINISTRATOR);
        }
        return describe(data.metadata(), target);
    }

    /** Returns an owner-first, UUID-ordered bounded member page; profile work touches only returned members. */
    public NetworkMemberPage members(ServerPlayer actor, UUID networkId, @Nullable UUID anchor, boolean backwards) {
        NetworkSavedData data = requireNetwork(actor, networkId);
        NetworkMetadata metadata = data.metadata();
        if (backwards && (anchor == null || anchor.equals(metadata.ownerId())))
            throw new IllegalArgumentException("Invalid member anchor");
        List<UUID> ids = new ArrayList<>(128);
        if (anchor == null) {
            ids.add(metadata.ownerId());
            ids.addAll(data.pageAdministratorIds(null, false, 127));
        } else if (anchor.equals(metadata.ownerId())) {
            ids.addAll(data.pageAdministratorIds(null, false, 128));
        } else {
            ids.addAll(data.pageAdministratorIds(anchor, backwards, 128));
            if (backwards && ids.size() < 128) ids.addFirst(metadata.ownerId());
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Member page is outside the collection");
        boolean hasPrevious = !ids.getFirst().equals(metadata.ownerId());
        UUID last = ids.getLast();
        boolean hasNext = last.equals(metadata.ownerId())
                ? data.administratorCount() > 0
                : !data.pageAdministratorIds(last, false, 1).isEmpty();
        List<NetworkMemberSummary> entries = new ArrayList<>(ids.size());
        for (UUID id : ids) entries.add(describe(metadata, id));
        return new NetworkMemberPage(entries, data.administratorCount() + 1, hasPrevious, hasNext);
    }

    /** Returns a page containing a newly committed member, without copying or searching all members. */
    public NetworkMemberPage membersIncluding(ServerPlayer actor, UUID networkId, UUID target) {
        NetworkSavedData data = requireNetwork(actor, networkId);
        if (data.metadata().ownerId().equals(target)) return members(actor, networkId, null, false);
        if (!data.metadata().administrators().contains(target)) throw rejected(Reason.NOT_ADMINISTRATOR);
        List<UUID> before = data.pageAdministratorIds(target, true, 1);
        return members(actor, networkId, before.isEmpty() ? null : before.getLast(), false);
    }

    private NetworkMemberSummary describe(NetworkMetadata metadata, UUID id) {
        Optional<PlayerIdentity> online = players.online(id);
        String name = online.map(PlayerIdentity::name).orElseGet(() -> players.knownName(id));
        return new NetworkMemberSummary(
                id,
                name,
                metadata.ownerId().equals(id)
                        ? NetworkMemberSummary.Role.OWNER
                        : NetworkMemberSummary.Role.ADMINISTRATOR,
                online.isPresent());
    }

    /**
     * Captures bounded online candidates for the owner, excluding existing members. The returned UUID-ordered
     * value list owns no live players and is a selection snapshot, not permission to add an offline player later.
     */
    public List<PlayerIdentity> onlineCandidates(ServerPlayer actor, UUID networkId) {
        NetworkMetadata network = requireOwner(actor, networkId).metadata();
        List<PlayerIdentity> online = players.snapshotOnline(MAXIMUM_CANDIDATES);
        if (online.size() > MAXIMUM_CANDIDATES) throw new IllegalArgumentException("Online roster exceeds hard limit");
        List<PlayerIdentity> candidates = new ArrayList<>();
        for (PlayerIdentity player : online) {
            if (!player.id().equals(network.ownerId())
                    && !network.administrators().contains(player.id())) candidates.add(player);
        }
        candidates.sort(Comparator.comparing(PlayerIdentity::id));
        return List.copyOf(candidates);
    }

    /**
     * Adds a currently online member after live owner, identity, quota and shared-lock validation. Pre-commit
     * rejection changes nothing. Success commits the shard and derived directory before releasing the lock;
     * unexpected commit failures are not retried or guessed. No simulation, packet, or file save occurs.
     */
    public NetworkMetadata add(ServerPlayer actor, UUID networkId, UUID target) {
        NetworkSavedData data = requireOwner(actor, networkId);
        requireTarget(data, target, true);
        if (players.online(target)
                .filter(profile -> profile.id().equals(target))
                .isEmpty()) throw rejected(Reason.PLAYER_OFFLINE);
        int limit = settings.administratorsPerNetwork();
        if (data.administratorCount() >= NetworkMetadata.MAXIMUM_ADMINISTRATORS
                || (limit != -1 && data.administratorCount() >= limit)) throw rejected(Reason.QUOTA_REACHED);
        EditLockTable.Token token = acquire(actor, networkId);
        try {
            return commit(data, target, true, data.managementRevision());
        } finally {
            locks.release(token, actor.getUUID());
        }
    }

    /** Acquires one removal-confirmation lease after validating current ownership and existing target membership. */
    public RemovalEdit beginRemoval(ServerPlayer actor, UUID networkId, UUID target) {
        NetworkSavedData data = requireOwner(actor, networkId);
        requireTarget(data, target, false);
        return new RemovalEdit(networkId, target, acquire(actor, networkId), data.managementRevision());
    }

    /**
     * Removes one exact revision's member, including offline members, and releases this edit on success/rejection.
     * The caller must end the removed player's scoped views after success; all further server actions already
     * observe revoked authority. Other networks, node configuration and shared-lock owners are not changed.
     */
    public NetworkMetadata remove(ServerPlayer actor, RemovalEdit edit) {
        requireActor(actor);
        Objects.requireNonNull(edit, "edit");
        try {
            NetworkSavedData data = requireRemoval(actor, edit);
            return commit(data, edit.target(), false, edit.revision());
        } finally {
            locks.release(edit.token(), actor.getUUID());
        }
    }

    /** Renews the exact current removal lease after rechecking ownership, existence and revision. */
    public void heartbeat(ServerPlayer actor, RemovalEdit edit) {
        requireRemoval(actor, edit);
        if (!locks.renew(edit.token(), actor.getUUID(), currentTick)) throw rejected(Reason.LOCK_EXPIRED);
    }

    /** Releases only this actor's exact token; no member data is modified, even if it is already expired. */
    public void cancel(ServerPlayer actor, RemovalEdit edit) {
        requireActor(actor);
        Objects.requireNonNull(edit, "edit");
        locks.release(edit.token(), actor.getUUID());
    }

    /** Advances this service's shared-lifecycle clock; the existing lock owner performs expiry cleanup. */
    public void tick() {
        requireOpen();
        currentTick = Math.incrementExact(currentTick);
    }

    /** Applies only newer validated settings from the same server epoch, never removing existing members. */
    public void applyConfiguration(ServerConfig.State state) {
        requireOpen();
        Objects.requireNonNull(state, "state");
        if (configurationLoaded
                && state.loaded()
                && state.epoch() == configurationEpoch
                && state.revision() > configurationRevision) {
            settings = Objects.requireNonNull(state.settings(), "settings");
            configurationRevision = state.revision();
        }
    }

    /** Ends this service without clearing the shared lock table; terminal owners cancel edits before shutdown. */
    @Override
    public void close() {
        requireServerThread();
        closed = true;
    }

    private NetworkMetadata commit(NetworkSavedData data, UUID target, boolean add, long revision) {
        NetworkSavedData.PreparedAdministratorChange prepared =
                data.prepareAdministratorChange(target, add, revision, settings.administratorsPerNetwork());
        NetworkDirectory.PreparedMetadataReplacement index =
                directory.prepareMetadataReplacement(prepared.previous(), prepared.next());
        NetworkMetadata result = data.commitAdministratorChange(prepared);
        directory.commitMetadataReplacement(index);
        return result;
    }

    private NetworkSavedData requireRemoval(ServerPlayer actor, RemovalEdit edit) {
        Objects.requireNonNull(edit, "edit");
        NetworkSavedData data = requireOwner(actor, edit.networkId());
        if (!locks.isHeld(edit.token(), actor.getUUID(), currentTick)) throw rejected(Reason.LOCK_EXPIRED);
        if (data.managementRevision() != edit.revision()) throw rejected(Reason.STALE_REVISION);
        requireTarget(data, edit.target(), false);
        return data;
    }

    private void requireTarget(NetworkSavedData data, UUID target, boolean add) {
        Objects.requireNonNull(target, "target");
        if (data.metadata().ownerId().equals(target)) throw rejected(Reason.OWNER_TARGET);
        boolean present = data.metadata().administrators().contains(target);
        if (present == add) throw rejected(add ? Reason.ALREADY_ADMINISTRATOR : Reason.NOT_ADMINISTRATOR);
    }

    private NetworkSavedData requireNetwork(ServerPlayer actor, UUID networkId) {
        requireActor(actor);
        Objects.requireNonNull(networkId, "networkId");
        NetworkMetadata indexed = directory.find(networkId).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        NetworkSavedData data = repository.findLoadedNetwork(networkId).orElseThrow(() -> rejected(Reason.UNAVAILABLE));
        if (!data.metadata().equals(indexed)) throw rejected(Reason.UNAVAILABLE);
        if (!actor.getUUID().equals(indexed.ownerId())
                && !indexed.administrators().contains(actor.getUUID())) throw rejected(Reason.NO_ACCESS);
        return data;
    }

    private NetworkSavedData requireOwner(ServerPlayer actor, UUID networkId) {
        NetworkSavedData data = requireNetwork(actor, networkId);
        if (!data.metadata().ownerId().equals(actor.getUUID())) throw rejected(Reason.NO_ACCESS);
        return data;
    }

    private EditLockTable.Token acquire(ServerPlayer actor, UUID networkId) {
        return locks.tryAcquire(NetworkManagementLockId.of(networkId), actor.getUUID(), currentTick)
                .orElseThrow(() -> rejected(Reason.LOCKED));
    }

    private void requireActor(ServerPlayer actor) {
        requireOpen();
        if (Objects.requireNonNull(actor, "actor").server != server)
            throw new IllegalArgumentException("Foreign server actor");
    }

    private void requireOpen() {
        requireServerThread();
        if (closed) throw rejected(Reason.UNAVAILABLE);
    }

    private void requireServerThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Membership accessed outside server thread");
    }

    private static Rejected rejected(Reason reason) {
        return new Rejected(reason);
    }
}
