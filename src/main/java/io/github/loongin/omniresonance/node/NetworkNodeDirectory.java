// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-thread-owned, nonpersistent UUID/position/chunk indexes derived from healthy network node records.
 *
 * <p>Construction copies immutable entries and detects startup conflicts without selecting a load-order winner.
 * Queries and runtime mutations perform no world access, simulation, I/O or implicit creation. Runtime updates
 * validate all affected keys before atomically changing the three indexes. Cross-thread access fails before state
 * reads or mutation. Conflict markers are session-only and bounded by the supplied record count.
 */
public final class NetworkNodeDirectory {
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkNodeDirectory.class);
    private static final Comparator<Entry> ORDER = Comparator.comparing(Entry::networkId)
            .thenComparingLong(entry -> entry.record().nodeNumber())
            .thenComparing(entry -> entry.record().nodeId());
    private final Thread owningThread = Thread.currentThread();
    private final Map<UUID, Entry> byId = new HashMap<>();
    private final Map<GlobalPos, Entry> byPosition = new HashMap<>();
    private final Map<ChunkKey, NavigableSet<Entry>> byChunk = new HashMap<>();
    private final Set<UUID> conflictedIds = new HashSet<>();
    private final Set<GlobalPos> conflictedPositions = new HashSet<>();

    /** Three observable lookup states without null or load-order ambiguity. */
    public enum Status {
        ABSENT,
        UNIQUE,
        CONFLICTED
    }

    /** Immutable network-scoped node reference with no world object ownership. */
    public record Entry(UUID networkId, NetworkNodeRecord record) {
        public Entry {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(record, "record");
        }
    }

    /** Immutable lookup result; only UNIQUE carries an entry. */
    public record Lookup(Status status, Optional<Entry> entry) {
        public Lookup {
            Objects.requireNonNull(status, "status");
            entry = Objects.requireNonNull(entry, "entry");
            if ((status == Status.UNIQUE) != entry.isPresent()) {
                throw new IllegalArgumentException("Only a unique lookup may carry an entry");
            }
        }

        private static Lookup absent() {
            return new Lookup(Status.ABSENT, Optional.empty());
        }

        private static Lookup conflicted() {
            return new Lookup(Status.CONFLICTED, Optional.empty());
        }

        private static Lookup unique(Entry entry) {
            return new Lookup(Status.UNIQUE, Optional.of(entry));
        }
    }

    /**
     * Builds all indexes on the constructing server thread without retaining the caller collection.
     * Null input/members fail before publication. Every entry touching a duplicate UUID or position is excluded
     * from unique indexes while unrelated entries remain available.
     */
    public NetworkNodeDirectory(Collection<Entry> entries) {
        List<Entry> copied = new ArrayList<>(Objects.requireNonNull(entries, "entries"));
        Map<UUID, Integer> idCounts = new HashMap<>();
        Map<GlobalPos, Integer> positionCounts = new HashMap<>();
        for (Entry entry : copied) {
            Objects.requireNonNull(entry, "entry");
            idCounts.merge(entry.record().nodeId(), 1, Integer::sum);
            positionCounts.merge(entry.record().position(), 1, Integer::sum);
        }
        for (Entry entry : copied) {
            if (idCounts.get(entry.record().nodeId()) > 1
                    || positionCounts.get(entry.record().position()) > 1) {
                conflictedIds.add(entry.record().nodeId());
                conflictedPositions.add(entry.record().position());
            }
        }
        for (Entry entry : copied) {
            if (!conflictedIds.contains(entry.record().nodeId())
                    && !conflictedPositions.contains(entry.record().position())) {
                index(entry);
            }
        }
        if (!conflictedIds.isEmpty() || !conflictedPositions.isEmpty()) {
            LOGGER.error(
                    "Blocked conflicting node authority keys at startup (node IDs={}, positions={})",
                    conflictedIds.size(),
                    conflictedPositions.size());
        }
    }

    /** Returns ABSENT, UNIQUE or CONFLICTED by UUID without mutation or world access. */
    public Lookup byId(UUID nodeId) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        if (conflictedIds.contains(nodeId)) {
            return Lookup.conflicted();
        }
        Entry entry = byId.get(nodeId);
        return entry == null ? Lookup.absent() : Lookup.unique(entry);
    }

    /** Returns ABSENT, UNIQUE or CONFLICTED by exact dimension/position without mutation or chunk loading. */
    public Lookup byPosition(GlobalPos position) {
        requireOwningThread();
        Objects.requireNonNull(position, "position");
        if (conflictedPositions.contains(position)) {
            return Lookup.conflicted();
        }
        Entry entry = byPosition.get(position);
        return entry == null ? Lookup.absent() : Lookup.unique(entry);
    }

    /** Returns a stable immutable snapshot of unique records in one dimension/chunk without world access. */
    public List<Entry> recordsInChunk(ResourceKey<Level> dimension, ChunkPos chunkPos) {
        requireOwningThread();
        ChunkKey key = new ChunkKey(
                Objects.requireNonNull(dimension, "dimension"), Objects.requireNonNull(chunkPos, "chunkPos"));
        NavigableSet<Entry> entries = byChunk.get(key);
        return entries == null ? List.of() : List.copyOf(entries);
    }

    /** Returns every unique entry in stable network/number/UUID order without exposing backing state. */
    public List<Entry> allUniqueEntries() {
        requireOwningThread();
        List<Entry> entries = new ArrayList<>(byId.values());
        entries.sort(ORDER);
        return List.copyOf(entries);
    }

    /** Adds one conflict-free authoritative entry to all indexes after complete validation. */
    public Entry add(Entry entry) {
        requireOwningThread();
        Objects.requireNonNull(entry, "entry");
        UUID nodeId = entry.record().nodeId();
        GlobalPos position = entry.record().position();
        if (conflictedIds.contains(nodeId)
                || conflictedPositions.contains(position)
                || byId.containsKey(nodeId)
                || byPosition.containsKey(position)) {
            throw new IllegalArgumentException("Duplicate or conflicted authoritative node key");
        }
        index(entry);
        return entry;
    }

    /**
     * Replaces only the physical form/facing snapshot for the exact current entry.
     * Network, UUID, number, name and position changes are rejected before any index mutation.
     */
    public Entry update(Entry previous, Entry updated) {
        requireOwningThread();
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(updated, "updated");
        Entry current = byId.get(previous.record().nodeId());
        if (!previous.equals(current)
                || !previous.networkId().equals(updated.networkId())
                || !previous.record().nodeId().equals(updated.record().nodeId())
                || previous.record().nodeNumber() != updated.record().nodeNumber()
                || !previous.record().name().equals(updated.record().name())
                || !previous.record().position().equals(updated.record().position())) {
            throw new IllegalArgumentException("Node directory update changed authority fields");
        }
        if (previous.equals(updated)) {
            return previous;
        }
        byId.put(updated.record().nodeId(), updated);
        byPosition.put(updated.record().position(), updated);
        NavigableSet<Entry> chunkEntries = byChunk.get(chunkKey(updated.record().position()));
        chunkEntries.remove(previous);
        chunkEntries.add(updated);
        return updated;
    }

    /** Removes only one unique exact network/UUID/position entry from all indexes. */
    public Optional<Entry> remove(UUID networkId, UUID nodeId, GlobalPos expectedPosition) {
        requireOwningThread();
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(expectedPosition, "expectedPosition");
        if (conflictedIds.contains(nodeId) || conflictedPositions.contains(expectedPosition)) {
            return Optional.empty();
        }
        Entry current = byId.get(nodeId);
        if (current == null
                || !current.networkId().equals(networkId)
                || !current.record().position().equals(expectedPosition)) {
            return Optional.empty();
        }
        byId.remove(nodeId);
        byPosition.remove(expectedPosition);
        ChunkKey chunkKey = chunkKey(expectedPosition);
        NavigableSet<Entry> chunkEntries = byChunk.get(chunkKey);
        chunkEntries.remove(current);
        if (chunkEntries.isEmpty()) {
            byChunk.remove(chunkKey);
        }
        return Optional.of(current);
    }

    private void index(Entry entry) {
        byId.put(entry.record().nodeId(), entry);
        byPosition.put(entry.record().position(), entry);
        byChunk.computeIfAbsent(chunkKey(entry.record().position()), ignored -> new TreeSet<>(ORDER))
                .add(entry);
    }

    private static ChunkKey chunkKey(GlobalPos position) {
        return new ChunkKey(position.dimension(), new ChunkPos(position.pos()));
    }

    private void requireOwningThread() {
        if (Thread.currentThread() != owningThread) {
            throw new IllegalStateException("Node directory accessed outside its owning server thread");
        }
    }

    private record ChunkKey(ResourceKey<Level> dimension, ChunkPos chunkPos) {}
}
