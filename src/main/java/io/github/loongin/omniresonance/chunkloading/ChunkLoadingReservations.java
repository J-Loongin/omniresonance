// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.chunkloading;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-thread reservation index rebuilt from saved enabled-loading flags. Keys are network/node pairs so
 * conflicting stored node identities cannot erase another scope's quota references. Size is bounded by saved
 * references; updates replace references, retain removes vanished records, and close clears the index. It never
 * loads worlds, grants tickets, mutates persistence, or queues denied requests. Quota checks are O(1).
 */
public final class ChunkLoadingReservations {
    public enum Admission {
        ALLOWED,
        OWNER_LIMIT,
        SERVER_LIMIT,
        SERVER_DISABLED,
        UNAVAILABLE
    }

    private record Key(UUID network, UUID node) {
        Key {
            java.util.Objects.requireNonNull(network);
            java.util.Objects.requireNonNull(node);
        }
    }

    private record Claim(UUID owner, ChunkLoadingAllocator.Chunk chunk) {
        Claim {
            java.util.Objects.requireNonNull(owner);
            java.util.Objects.requireNonNull(chunk);
        }
    }

    private final Thread thread = Thread.currentThread();
    private final Map<Key, Claim> claims = new HashMap<>();
    private final Map<UUID, Set<Key>> networks = new HashMap<>();
    private final Map<UUID, Map<ChunkLoadingAllocator.Chunk, Integer>> owners = new HashMap<>();
    private final Map<ChunkLoadingAllocator.Chunk, Integer> physical = new HashMap<>();

    private void checkThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Reservations off owner thread");
    }
    /** Restores an authoritative existing entitlement, including one grandfathered above the current limit. */
    public void put(UUID network, UUID node, UUID owner, ChunkLoadingAllocator.Chunk chunk) {
        checkThread();
        var key = new Key(network, node);
        var claim = new Claim(owner, chunk);
        if (claim.equals(claims.get(key))) return;
        remove(key);
        claims.put(key, claim);
        networks.computeIfAbsent(network, ignored -> new HashSet<>()).add(key);
        owners.computeIfAbsent(owner, ignored -> new HashMap<>()).merge(chunk, 1, Math::addExact);
        physical.merge(chunk, 1, Math::addExact);
    }

    public void retain(UUID network, Set<UUID> retained) {
        checkThread();
        var keys = networks.get(network);
        if (keys == null) return;
        var iterator = keys.iterator();
        while (iterator.hasNext()) {
            var key = iterator.next();
            if (!retained.contains(key.node())) {
                iterator.remove();
                removeClaim(key);
            }
        }
        if (keys.isEmpty()) networks.remove(network);
    }

    private void remove(Key key) {
        var scope = networks.get(key.network());
        if (scope != null) {
            scope.remove(key);
            if (scope.isEmpty()) networks.remove(key.network());
        }
        removeClaim(key);
    }

    private void removeClaim(Key key) {
        var old = claims.remove(key);
        if (old == null) return;
        var owner = owners.get(old.owner());
        decrement(owner, old.chunk());
        if (owner.isEmpty()) owners.remove(old.owner());
        decrement(physical, old.chunk());
    }

    private static void decrement(Map<ChunkLoadingAllocator.Chunk, Integer> refs, ChunkLoadingAllocator.Chunk chunk) {
        int next = refs.get(chunk) - 1;
        if (next == 0) refs.remove(chunk);
        else refs.put(chunk, next);
    }
    /** Read-only incremental admission; rejected attempts never become reservations or pending work. */
    public Admission check(UUID owner, ChunkLoadingAllocator.Chunk chunk, ChunkLoadingAllocator.Limits limits) {
        checkThread();
        if (!limits.enabled()) return Admission.SERVER_DISABLED;
        var groups = owners.get(owner);
        if ((groups == null || !groups.containsKey(chunk))
                && limits.perOwner() >= 0
                && ownerCount(owner) >= limits.perOwner()) return Admission.OWNER_LIMIT;
        if (!physical.containsKey(chunk) && limits.server() >= 0 && physical.size() >= limits.server())
            return Admission.SERVER_LIMIT;
        return Admission.ALLOWED;
    }

    public boolean owns(UUID network, UUID node, UUID owner, ChunkLoadingAllocator.Chunk chunk) {
        checkThread();
        return new Claim(owner, chunk).equals(claims.get(new Key(network, node)));
    }

    public int ownerCount(UUID owner) {
        checkThread();
        var groups = owners.get(owner);
        return groups == null ? 0 : groups.size();
    }

    public int serverCount() {
        checkThread();
        return physical.size();
    }

    public void clear() {
        checkThread();
        claims.clear();
        networks.clear();
        owners.clear();
        physical.clear();
    }
}
