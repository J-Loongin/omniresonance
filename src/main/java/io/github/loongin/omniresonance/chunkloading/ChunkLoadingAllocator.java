// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.chunkloading;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Owner-thread, nonpersistent allocation state. Callers supply current authoritative eligibility; this class
 * never loads worlds, changes requests in SavedData, or issues tickets. One work unit examines at most one
 * owner/chunk and grants at most one owner quota unit. Node, owner/chunk and physical references have separate
 * lifetimes; no polling copies, history, simulation promises or per-resource work are retained.
 */
public final class ChunkLoadingAllocator {
    public record Chunk(ResourceLocation dimension, int x, int z) implements Comparable<Chunk> {
        public Chunk {
            Objects.requireNonNull(dimension);
        }

        public int compareTo(Chunk other) {
            int order = dimension.compareTo(other.dimension);
            if (order == 0) order = Integer.compare(x, other.x);
            return order == 0 ? Integer.compare(z, other.z) : order;
        }
    }

    public record Limits(boolean enabled, int perOwner, int server) {
        public Limits {
            if (perOwner < -1 || perOwner > 100000 || server < -1 || server > 1000000)
                throw new IllegalArgumentException("Invalid chunk quotas");
        }
    }

    public enum Eligibility {
        READY,
        OFF,
        NODE_DISABLED,
        DOMAIN_UNAVAILABLE,
        UNAVAILABLE
    }

    public enum Status {
        OFF,
        QUEUED,
        ACTIVE,
        OWNER_LIMIT,
        SERVER_LIMIT,
        SERVER_DISABLED,
        NODE_DISABLED,
        DOMAIN_UNAVAILABLE,
        UNAVAILABLE
    }

    public record Request(UUID node, UUID owner, Chunk chunk, Eligibility eligibility) {
        public Request {
            Objects.requireNonNull(node);
            Objects.requireNonNull(owner);
            Objects.requireNonNull(chunk);
            Objects.requireNonNull(eligibility);
        }
    }

    private final Thread thread = Thread.currentThread();
    private final Map<UUID, Request> requests = new HashMap<>();
    private final TreeMap<UUID, Owner> owners = new TreeMap<>();
    private final TreeMap<Chunk, TreeSet<UUID>> physical = new TreeMap<>();
    private final NavigableSet<Chunk> physicalView = Collections.unmodifiableNavigableSet(physical.navigableKeySet());
    private final java.util.LinkedHashMap<Chunk, Boolean> transitions = new java.util.LinkedHashMap<>();
    private final java.util.Set<Chunk> delivered = new java.util.HashSet<>();
    private Limits limits;

    public record Transition(Chunk chunk, boolean acquire) {}
    /** Consumes coalesced physical deltas; retained keys are only desired or previously delivered physical chunks. */
    public @Nullable Transition pollTransition() {
        check();
        var iterator = transitions.entrySet().iterator();
        if (!iterator.hasNext()) return null;
        var entry = iterator.next();
        var change = new Transition(entry.getKey(), entry.getValue());
        iterator.remove();
        if (change.acquire()) delivered.add(change.chunk());
        else delivered.remove(change.chunk());
        return change;
    }

    private void physicalChanged(Chunk chunk) {
        boolean present = physical.containsKey(chunk);
        if (present == delivered.contains(chunk)) transitions.remove(chunk);
        else transitions.put(chunk, present);
    }

    private @Nullable UUID nextAfter;
    private long revision;

    public ChunkLoadingAllocator(Limits limits) {
        this.limits = Objects.requireNonNull(limits);
    }

    /** Replaces one request without accessing authority; unchanged requests are constant-time no-ops. */
    public void put(Request request) {
        check();
        Objects.requireNonNull(request);
        Request old = requests.get(request.node());
        if (request.equals(old)) return;
        remove(request.node());
        requests.put(request.node(), request);
        if (request.eligibility() != Eligibility.READY) return;
        var owner = owners.computeIfAbsent(request.owner(), ignored -> new Owner());
        var nodes = owner.groups.computeIfAbsent(request.chunk(), ignored -> new HashSet<>());
        nodes.add(request.node());
        if (!owner.granted.contains(request.chunk())) owner.waiting.add(request.chunk());
    }

    /** Removes only this node's reference; another owner or node can keep the same physical allocation alive. */
    public void remove(UUID node) {
        check();
        Request old = requests.remove(node);
        if (old == null || old.eligibility() != Eligibility.READY) return;
        var owner = owners.get(old.owner());
        var refs = owner.groups.get(old.chunk());
        refs.remove(node);
        if (refs.isEmpty()) {
            if (owner.granted.contains(old.chunk())) revoke(old.owner(), owner, old.chunk());
            owner.waiting.remove(old.chunk());
            owner.groups.remove(old.chunk());
        }
        if (owner.groups.isEmpty()) owners.remove(old.owner());
    }

    /** Applies quotas immediately to desired allocations; requested state is retained. No native ticket calls occur. */
    public void limits(Limits updated) {
        check();
        Objects.requireNonNull(updated);
        if (limits.equals(updated)) return;
        limits = updated;
        for (var entry : owners.entrySet()) {
            var owner = entry.getValue();
            int allowed = limits.enabled() ? limits.perOwner() : 0;
            while (allowed >= 0 && owner.granted.size() > allowed) revoke(entry.getKey(), owner, owner.granted.last());
        }
        while (limits.server() >= 0 && physical.size() > limits.server()) {
            Chunk chunk = physical.lastKey();
            var refs = physical.get(chunk);
            while (!refs.isEmpty()) {
                UUID id = refs.first();
                revoke(id, owners.get(id), chunk);
            }
        }
    }

    /** Grants within a caller-supplied work budget, resuming owner and candidate cursors after quota pressure. */
    public int advance(int maximumChecks) {
        check();
        if (maximumChecks < 0) throw new IllegalArgumentException("Negative allocation work budget");
        if (!limits.enabled() || owners.isEmpty()) return 0;
        int granted = 0, idle = 0;
        for (int work = 0; work < maximumChecks; work++) {
            var entry = nextAfter == null ? owners.firstEntry() : owners.higherEntry(nextAfter);
            if (entry == null) entry = owners.firstEntry();
            nextAfter = entry.getKey();
            var owner = entry.getValue();
            boolean changed = false;
            if (!owner.waiting.isEmpty() && !full(owner.granted.size(), limits.perOwner())) {
                Chunk candidate = owner.cursor == null ? owner.waiting.first() : owner.waiting.higher(owner.cursor);
                if (candidate == null) candidate = owner.waiting.first();
                owner.cursor = candidate;
                if (physical.containsKey(candidate) || !full(physical.size(), limits.server())) {
                    revision = Math.incrementExact(revision);
                    owner.waiting.remove(candidate);
                    owner.granted.add(candidate);
                    physical.computeIfAbsent(candidate, ignored -> new TreeSet<>())
                            .add(entry.getKey());
                    physicalChanged(candidate);
                    granted++;
                    changed = true;
                }
            }
            idle = changed ? 0 : idle + 1;
            if (idle >= owners.size()) break;
        }
        return granted;
    }

    public Status status(UUID node) {
        check();
        Request request = requests.get(node);
        if (request == null) return Status.OFF;
        if (request.eligibility() != Eligibility.READY)
            return switch (request.eligibility()) {
                case OFF -> Status.OFF;
                case NODE_DISABLED -> Status.NODE_DISABLED;
                case DOMAIN_UNAVAILABLE -> Status.DOMAIN_UNAVAILABLE;
                case UNAVAILABLE -> Status.UNAVAILABLE;
                case READY -> throw new IllegalStateException("Unexpected ready branch");
            };
        if (!limits.enabled()) return Status.SERVER_DISABLED;
        var owner = owners.get(request.owner());
        if (owner.granted.contains(request.chunk())) return Status.ACTIVE;
        if (full(owner.granted.size(), limits.perOwner())) return Status.OWNER_LIMIT;
        if (!physical.containsKey(request.chunk()) && full(physical.size(), limits.server()))
            return Status.SERVER_LIMIT;
        return Status.QUEUED;
    }

    /** Borrowed read-only live set, valid only on the owner thread; callers must not retain iterators across mutation. */
    public NavigableSet<Chunk> physicalChunks() {
        check();
        return physicalView;
    }

    public int physicalCount() {
        check();
        return physical.size();
    }

    public int ownerCount(UUID owner) {
        check();
        var state = owners.get(owner);
        return state == null ? 0 : state.granted.size();
    }

    public int requestCount() {
        check();
        return requests.size();
    }

    public long revision() {
        check();
        return revision;
    }

    private void revoke(UUID id, Owner owner, Chunk chunk) {
        revision = Math.incrementExact(revision);
        owner.granted.remove(chunk);
        owner.waiting.add(chunk);
        var refs = physical.get(chunk);
        refs.remove(id);
        if (refs.isEmpty()) physical.remove(chunk);
        physicalChanged(chunk);
    }

    private static boolean full(int used, int limit) {
        return limit >= 0 && used >= limit;
    }

    private void check() {
        if (Thread.currentThread() != thread)
            throw new IllegalStateException("Chunk allocation accessed off owner thread");
    }

    private static final class Owner {
        final Map<Chunk, HashSet<UUID>> groups = new HashMap<>();
        final TreeSet<Chunk> granted = new TreeSet<>(), waiting = new TreeSet<>();

        @Nullable
        Chunk cursor;
    }
}
