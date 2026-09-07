// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Derived, nonpersistent directory owned by the server thread that constructs it.
 *
 * <p>Indexes live only for this directory's lifetime, keyed by network, owner, player, and owner/name. Their size
 * derives from verified metadata, with at most 262144 networks per owner; no query adds entries. Updates happen
 * only through validated additions or prepared replacements after an authoritative commit. No operation is a simulation, and no method
 * touches disk. Immutable snapshots may be shared; wrong-thread access fails before reading or changing indexes.
 * Owners affected by startup conflicts remain creation-blocked until a fresh directory is built after restart.
 */
public final class NetworkDirectory {
    /** Owner-bound preflight for a name-only metadata replacement; performs no mutation or I/O. */
    public static final class PreparedRename {
        private final NetworkDirectory owner;
        private final NetworkMetadata previous;
        private final NetworkMetadata next;

        private PreparedRename(NetworkDirectory owner, NetworkMetadata previous, NetworkMetadata next) {
            this.owner = owner;
            this.previous = previous;
            this.next = next;
        }
    }

    /** Owner-bound preflight for removing one exact indexed network; performs no mutation or I/O. */
    public static final class PreparedRemoval {
        private final NetworkDirectory owner;
        private final NetworkMetadata previous;

        private PreparedRemoval(NetworkDirectory owner, NetworkMetadata previous) {
            this.owner = owner;
            this.previous = previous;
        }
    }

    /** Owner-bound preflight result. It retains immutable metadata, changes no index and performs no I/O. */
    public static final class PreparedMetadataReplacement {
        private final NetworkDirectory owner;
        private final NetworkMetadata previous;
        private final NetworkMetadata next;
        private final List<UUID> removed;
        private final List<UUID> added;

        private PreparedMetadataReplacement(
                NetworkDirectory owner,
                NetworkMetadata previous,
                NetworkMetadata next,
                List<UUID> removed,
                List<UUID> added) {
            this.owner = owner;
            this.previous = previous;
            this.next = next;
            this.removed = List.copyOf(removed);
            this.added = List.copyOf(added);
        }
    }

    static final int MAXIMUM_NETWORKS_PER_OWNER = 262144;
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkDirectory.class);
    private static final Comparator<NetworkMetadata> ORDER =
            Comparator.comparingLong(NetworkMetadata::creationOrder).thenComparing(NetworkMetadata::id);
    private final Thread owningThread = Thread.currentThread();
    private final Map<UUID, NetworkMetadata> networks = new HashMap<>();
    private final Map<UUID, NavigableSet<NetworkMetadata>> byOwner = new HashMap<>();
    private final Map<UUID, NavigableSet<UUID>> byPlayer = new HashMap<>();
    private final Map<ScopedName, UUID> byName = new HashMap<>();
    private final Set<UUID> creationBlockedOwners = new HashSet<>();

    /**
     * Builds owned indexes from caller-owned immutable snapshots on the server thread.
     *
     * <p>All entries sharing a duplicate identity or owner/name are excluded and logged, retaining unrelated valid
     * entries. An oversized owner's entire collection is excluded, never truncated. Conflicts/oversized sets
     * block new creation for affected owners so exclusions cannot release quota or names. Null input or entries
     * throw before construction; input is not retained or mutated and no simulation or persistent mutation occurs.
     */
    public NetworkDirectory(Collection<NetworkMetadata> initialNetworks) {
        List<NetworkMetadata> sorted = new ArrayList<>(Objects.requireNonNull(initialNetworks, "initialNetworks"));
        Set<UUID> seenIds = new HashSet<>();
        Set<UUID> duplicateIds = new HashSet<>();
        Set<ScopedName> seenNames = new HashSet<>();
        Set<ScopedName> duplicateNames = new HashSet<>();
        Map<UUID, Integer> ownerCounts = new HashMap<>();
        Set<UUID> oversizedOwners = new HashSet<>();
        for (NetworkMetadata metadata : sorted) {
            Objects.requireNonNull(metadata, "metadata");
            int count = ownerCounts.merge(metadata.ownerId(), 1, Integer::sum);
            if (count == MAXIMUM_NETWORKS_PER_OWNER + 1) {
                oversizedOwners.add(metadata.ownerId());
                creationBlockedOwners.add(metadata.ownerId());
                LOGGER.error(
                        "Excluded network collection for owner {} exceeding hard limit {}",
                        metadata.ownerId(),
                        MAXIMUM_NETWORKS_PER_OWNER);
            }
            if (!seenIds.add(metadata.id())) {
                duplicateIds.add(metadata.id());
            }
            ScopedName name = new ScopedName(metadata.ownerId(), metadata.name().uniquenessKey());
            if (!seenNames.add(name)) {
                duplicateNames.add(name);
            }
        }
        sorted.sort(ORDER);
        for (NetworkMetadata metadata : sorted) {
            if (oversizedOwners.contains(metadata.ownerId())) {
                continue;
            }
            if (duplicateIds.contains(metadata.id())
                    || duplicateNames.contains(
                            new ScopedName(metadata.ownerId(), metadata.name().uniquenessKey()))) {
                LOGGER.error("Excluded conflicting network metadata {}", metadata.id());
                creationBlockedOwners.add(metadata.ownerId());
                continue;
            }
            add(metadata);
        }
    }

    /** Returns an immutable ordered snapshot; caller inputs stay owned, no mutation/simulation occurs. */
    public List<NetworkMetadata> ownedBy(UUID owner) {
        requireOwningThread();
        NavigableSet<NetworkMetadata> owned = byOwner.get(Objects.requireNonNull(owner, "owner"));
        return owned == null ? List.of() : List.copyOf(owned);
    }

    /** Returns only explicit owner/administrator access in creation order, without mutation or OP inference. */
    public List<NetworkMetadata> accessibleTo(UUID player) {
        requireOwningThread();
        NavigableSet<UUID> accessible = byPlayer.get(Objects.requireNonNull(player, "player"));
        if (accessible == null) {
            return List.of();
        }
        List<NetworkMetadata> result = new ArrayList<>(accessible.size());
        for (UUID id : accessible) {
            result.add(networks.get(id));
        }
        return List.copyOf(result);
    }

    /**
     * Reads a bounded, independently owned window on the owning server thread without mutation or I/O.
     * Anchors must currently be accessible; invalid anchors, direction and limits throw before reading a page.
     * Tree navigation costs O(log N + limit), copying only the requested window; no history is retained.
     */
    public AccessPage pageAccessible(UUID player, @Nullable UUID anchor, boolean backwards, int limit) {
        requireOwningThread();
        Objects.requireNonNull(player, "player");
        if (limit < 1 || limit > 2048 || (backwards && anchor == null)) {
            throw new IllegalArgumentException("Invalid directory page bounds");
        }
        NavigableSet<UUID> accessible = byPlayer.get(player);
        if (anchor != null && (accessible == null || !networks.containsKey(anchor) || !accessible.contains(anchor))) {
            throw new IllegalArgumentException("Inaccessible directory anchor");
        }
        if (accessible == null || accessible.isEmpty()) {
            return new AccessPage(List.of(), 0, false, false);
        }
        NavigableSet<UUID> window = anchor == null
                ? accessible
                : backwards ? accessible.headSet(anchor, false).descendingSet() : accessible.tailSet(anchor, false);
        List<NetworkMetadata> entries = new ArrayList<>(Math.min(limit, accessible.size()));
        Iterator<UUID> iterator = window.iterator();
        while (entries.size() < limit && iterator.hasNext()) {
            entries.add(networks.get(iterator.next()));
        }
        if (backwards) {
            Collections.reverse(entries);
        }
        boolean hasPrevious;
        boolean hasNext;
        if (entries.isEmpty()) {
            hasPrevious = !backwards;
            hasNext = backwards;
        } else {
            hasPrevious = accessible.lower(entries.getFirst().id()) != null;
            hasNext = accessible.higher(entries.getLast().id()) != null;
        }
        return new AccessPage(entries, accessible.size(), hasPrevious, hasNext);
    }

    /** Reads the owner index size in O(1) on its server thread, without copying or mutating any state. */
    public int ownedCount(UUID owner) {
        requireOwningThread();
        NavigableSet<NetworkMetadata> owned = byOwner.get(Objects.requireNonNull(owner, "owner"));
        return owned == null ? 0 : owned.size();
    }

    /**
     * Returns the earliest owned network without copying the owner index.
     *
     * <p>Runs only on the constructing server thread. The returned immutable metadata remains owned by this
     * directory and may be retained by the caller. This query performs no simulation, mutation, or I/O. A missing
     * owner returns empty; null and wrong-thread access fail before reading the index.
     */
    public Optional<NetworkMetadata> firstOwned(UUID owner) {
        requireOwningThread();
        NavigableSet<NetworkMetadata> owned = byOwner.get(Objects.requireNonNull(owner, "owner"));
        return owned == null || owned.isEmpty() ? Optional.empty() : Optional.of(owned.first());
    }

    /**
     * Returns the earliest owned network other than one exact identity in O(log N), without copying or mutation.
     * The excluded identity need not be present; null and wrong-thread calls reject before index access.
     */
    public Optional<NetworkMetadata> firstOwnedExcluding(UUID owner, UUID excluded) {
        requireOwningThread();
        NavigableSet<NetworkMetadata> owned = byOwner.get(Objects.requireNonNull(owner, "owner"));
        Objects.requireNonNull(excluded, "excluded");
        if (owned == null || owned.isEmpty()) {
            return Optional.empty();
        }
        NetworkMetadata first = owned.first();
        if (!first.id().equals(excluded)) {
            return Optional.of(first);
        }
        return Optional.ofNullable(owned.higher(first));
    }

    /** Immutable bounded server-thread query result; construction copies only its page and has no side effects. */
    public record AccessPage(List<NetworkMetadata> entries, int totalCount, boolean hasPrevious, boolean hasNext) {
        public AccessPage {
            if (entries.size() > 2048 || totalCount < 0) {
                throw new IllegalArgumentException("Invalid directory page");
            }
            entries = List.copyOf(entries);
        }
    }

    /** Returns an immutable snapshot if verified, without mutation or treating missing data as deleted. */
    public Optional<NetworkMetadata> find(UUID id) {
        requireOwningThread();
        return Optional.ofNullable(networks.get(Objects.requireNonNull(id, "id")));
    }

    /** Checks owner-scoped ASCII-insensitive uniqueness without modifying inputs or indexes. */
    public boolean containsName(UUID owner, ManagedName name) {
        requireOwningThread();
        return byName.containsKey(new ScopedName(Objects.requireNonNull(owner, "owner"), name.uniquenessKey()));
    }

    /** Computes the next order without mutation; exhausted long range throws before a commit. */
    public long nextCreationOrder(UUID owner) {
        requireOwningThread();
        NavigableSet<NetworkMetadata> owned = byOwner.get(Objects.requireNonNull(owner, "owner"));
        return owned == null ? 0 : Math.incrementExact(owned.last().creationOrder());
    }

    /**
     * Adds one immutable snapshot following an authoritative commit on the owning server thread.
     *
     * <p>Duplicate IDs/names and owner hard-limit excess throw {@link IllegalArgumentException} before any index
     * changes; null input throws {@link NullPointerException}. No simulation, caller mutation, or I/O occurs.
     */
    public void add(NetworkMetadata metadata) {
        requireOwningThread();
        Objects.requireNonNull(metadata, "metadata");
        ScopedName name = new ScopedName(metadata.ownerId(), metadata.name().uniquenessKey());
        NavigableSet<NetworkMetadata> owned = byOwner.get(metadata.ownerId());
        if (networks.containsKey(metadata.id()) || byName.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate network identity or owner-scoped name");
        }
        if (owned != null && owned.size() >= MAXIMUM_NETWORKS_PER_OWNER) {
            throw new IllegalArgumentException("Owner network collection hard limit reached");
        }
        networks.put(metadata.id(), metadata);
        byName.put(name, metadata.id());
        byOwner.computeIfAbsent(metadata.ownerId(), ignored -> new TreeSet<>(ORDER))
                .add(metadata);
        addAccessible(metadata.ownerId(), metadata.id());
        for (UUID administrator : metadata.administrators()) {
            addAccessible(administrator, metadata.id());
        }
    }

    private void addAccessible(UUID player, UUID network) {
        byPlayer.computeIfAbsent(
                        player,
                        ignored ->
                                new TreeSet<>((left, right) -> ORDER.compare(networks.get(left), networks.get(right))))
                .add(network);
    }

    /**
     * Preflights a membership-only metadata replacement on the owning server thread, without modifying indexes.
     * Identity, owner, creation order and name must stay fixed. Stale/mismatched inputs are rejected before a
     * caller commits authoritative data. Cost is O(A), where A is this network's administrator count, not all networks.
     */
    public PreparedMetadataReplacement prepareMetadataReplacement(NetworkMetadata previous, NetworkMetadata next) {
        requireOwningThread();
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        NetworkMetadata current = networks.get(previous.id());
        if (!previous.equals(current)
                || !previous.id().equals(next.id())
                || !previous.ownerId().equals(next.ownerId())
                || previous.creationOrder() != next.creationOrder()
                || !previous.name().equals(next.name())
                || previous.equals(next)) {
            throw new IllegalArgumentException("Invalid network metadata replacement");
        }
        List<UUID> removed = new ArrayList<>();
        List<UUID> added = new ArrayList<>();
        for (UUID player : previous.administrators()) {
            if (!next.administrators().contains(player)) {
                removed.add(player);
            }
        }
        for (UUID player : next.administrators()) {
            if (!previous.administrators().contains(player)) {
                added.add(player);
            }
        }
        return new PreparedMetadataReplacement(this, current, next, removed, added);
    }

    /**
     * Applies an owner-bound preflight immediately after its authoritative commit on the same server thread.
     * Wrong-directory or superseded values fail before mutation. Only this network and changed players' access
     * sets are updated; other networks, owner names and ordering remain unchanged. No simulation or I/O occurs.
     */
    public void commitMetadataReplacement(PreparedMetadataReplacement prepared) {
        requireOwningThread();
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.owner != this || networks.get(prepared.previous.id()) != prepared.previous) {
            throw new IllegalArgumentException("Stale or foreign metadata replacement");
        }
        UUID id = prepared.previous.id();
        for (UUID player : prepared.removed) {
            NavigableSet<UUID> accessible = byPlayer.get(player);
            accessible.remove(id);
            if (accessible.isEmpty()) {
                byPlayer.remove(player);
            }
        }
        NavigableSet<NetworkMetadata> owned = byOwner.get(prepared.previous.ownerId());
        owned.remove(prepared.previous);
        networks.put(id, prepared.next);
        owned.add(prepared.next);
        for (UUID player : prepared.added) {
            addAccessible(player, id);
        }
    }

    /**
     * Preflights a name-only replacement without changing any index. Identity, owner, creation order and members
     * must remain exact; unchanged and owner-scoped duplicate names reject before authoritative mutation.
     */
    public PreparedRename prepareRename(NetworkMetadata previous, NetworkMetadata next) {
        requireOwningThread();
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        NetworkMetadata current = networks.get(previous.id());
        if (current != previous
                || !previous.id().equals(next.id())
                || !previous.ownerId().equals(next.ownerId())
                || previous.creationOrder() != next.creationOrder()
                || !previous.administrators().equals(next.administrators())
                || previous.name().equals(next.name())) {
            throw new IllegalArgumentException("Invalid network rename");
        }
        ScopedName name = new ScopedName(next.ownerId(), next.name().uniquenessKey());
        if (byName.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate owner-scoped network name");
        }
        return new PreparedRename(this, previous, next);
    }

    /**
     * Applies one exact name-only preflight after its authoritative shard commit. Other network and player access
     * indexes remain unchanged; stale or foreign values reject before mutation and no I/O occurs.
     */
    public void commitRename(PreparedRename prepared) {
        requireOwningThread();
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.owner != this || networks.get(prepared.previous.id()) != prepared.previous) {
            throw new IllegalArgumentException("Stale or foreign network rename");
        }
        ScopedName previousName = new ScopedName(
                prepared.previous.ownerId(), prepared.previous.name().uniquenessKey());
        ScopedName nextName =
                new ScopedName(prepared.next.ownerId(), prepared.next.name().uniquenessKey());
        if (!Objects.equals(byName.get(previousName), prepared.previous.id()) || byName.containsKey(nextName)) {
            throw new IllegalArgumentException("Network name index changed after preflight");
        }
        NavigableSet<NetworkMetadata> owned = byOwner.get(prepared.previous.ownerId());
        if (owned == null || !owned.remove(prepared.previous)) {
            throw new IllegalArgumentException("Network owner index changed after preflight");
        }
        byName.remove(previousName);
        networks.put(prepared.previous.id(), prepared.next);
        byName.put(nextName, prepared.next.id());
        owned.add(prepared.next);
    }

    /** Preflights removal of one exact current metadata object without modifying indexes or persistent state. */
    public PreparedRemoval prepareRemoval(NetworkMetadata previous) {
        requireOwningThread();
        Objects.requireNonNull(previous, "previous");
        if (networks.get(previous.id()) != previous) {
            throw new IllegalArgumentException("Stale or absent network removal");
        }
        return new PreparedRemoval(this, previous);
    }

    /**
     * Removes one exact preflight from every derived index. Other networks and their access remain unchanged;
     * wrong-directory, stale, or reused values reject before mutation and no I/O occurs.
     */
    public void commitRemoval(PreparedRemoval prepared) {
        requireOwningThread();
        Objects.requireNonNull(prepared, "prepared");
        NetworkMetadata previous = prepared.previous;
        if (prepared.owner != this || networks.get(previous.id()) != previous) {
            throw new IllegalArgumentException("Stale or foreign network removal");
        }
        ScopedName name = new ScopedName(previous.ownerId(), previous.name().uniquenessKey());
        NavigableSet<NetworkMetadata> owned = byOwner.get(previous.ownerId());
        if (!Objects.equals(byName.get(name), previous.id()) || owned == null || !owned.contains(previous)) {
            throw new IllegalArgumentException("Network indexes changed after removal preflight");
        }
        requireAccessible(previous.ownerId(), previous.id());
        for (UUID administrator : previous.administrators()) {
            requireAccessible(administrator, previous.id());
        }
        removeAccessible(previous.ownerId(), previous.id());
        for (UUID administrator : previous.administrators()) {
            removeAccessible(administrator, previous.id());
        }
        owned.remove(previous);
        if (owned.isEmpty()) {
            byOwner.remove(previous.ownerId());
        }
        byName.remove(name);
        networks.remove(previous.id());
    }

    private void requireAccessible(UUID player, UUID network) {
        NavigableSet<UUID> accessible = byPlayer.get(player);
        if (accessible == null || !accessible.contains(network)) {
            throw new IllegalArgumentException("Network access index changed after removal preflight");
        }
    }

    private void removeAccessible(UUID player, UUID network) {
        NavigableSet<UUID> accessible = byPlayer.get(player);
        if (accessible == null || !accessible.remove(network)) {
            throw new IllegalArgumentException("Network access index changed after removal preflight");
        }
        if (accessible.isEmpty()) {
            byPlayer.remove(player);
        }
    }

    void requireCreationAllowed(UUID owner) {
        requireOwningThread();
        if (creationBlockedOwners.contains(Objects.requireNonNull(owner, "owner"))) {
            throw new IllegalStateException("Owner network collection is inconsistent; new creation is unavailable");
        }
    }

    private void requireOwningThread() {
        if (Thread.currentThread() != owningThread) {
            throw new IllegalStateException("Network directory accessed outside its owning server thread");
        }
    }

    private record ScopedName(UUID owner, String name) {}
}
