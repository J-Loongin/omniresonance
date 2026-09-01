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
 * only through validated additions after an authoritative commit. No operation is a simulation, and no method
 * touches disk. Immutable snapshots may be shared; wrong-thread access fails before reading or changing indexes.
 * Owners affected by startup conflicts remain creation-blocked until a fresh directory is built after restart.
 */
public final class NetworkDirectory {
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
