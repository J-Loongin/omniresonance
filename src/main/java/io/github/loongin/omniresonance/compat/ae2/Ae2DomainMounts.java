// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One server runtime's loaded interface membership. Grid keys use object identity, domain and endpoint keys use
 * stable UUIDs. At most capacity endpoints/groups are retained; remove/unbind/unload and clear retire references.
 * Permission/count reads are O(1), with no world access, scanning, simulation or mutation. Topology changes return
 * a detached affected set in O(old group + new group) work so callers can invalidate mounts after atomic publication.
 * Power/channel loss must not remove membership: otherwise withdrawing a conflicting mount would oscillate.
 */
public final class Ae2DomainMounts {
    public record Change(Set<UUID> affected) {
        public Change {
            affected = Set.copyOf(affected);
        }
    }

    private static final class Key {
        final Object grid;
        final UUID domain;

        Key(Object grid, UUID domain) {
            this.grid = Objects.requireNonNull(grid);
            this.domain = Objects.requireNonNull(domain);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && grid == key.grid && domain.equals(key.domain);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(grid) + domain.hashCode();
        }
    }

    private static final class Group {
        final Key key;
        final Set<UUID> members = new HashSet<>();

        Group(Key key) {
            this.key = key;
        }
    }

    private final Thread owner = Thread.currentThread();
    private final int capacity;
    private final Map<UUID, Group> endpoints = new HashMap<>();
    private final Map<Key, Group> groups = new HashMap<>();

    public Ae2DomainMounts(int capacity) {
        if (capacity < 1 || capacity > 262144)
            throw new IllegalArgumentException("Invalid interface registry capacity");
        this.capacity = capacity;
    }
    /** Records already-authorized, loaded membership; capacity rejection and invalid inputs leave it unchanged. */
    public Change bind(UUID endpoint, Object grid, UUID domain) {
        check();
        Objects.requireNonNull(endpoint);
        Key key = new Key(grid, domain);
        Group old = endpoints.get(endpoint);
        if (old != null && old.key.equals(key)) return new Change(Set.of());
        if (old == null && endpoints.size() >= capacity)
            throw new IllegalStateException("Interface registry capacity reached");
        Group next = groups.get(key);
        if (next == null) next = new Group(key);
        var affected = new HashSet<>(next.members);
        if (old != null) affected.addAll(old.members);
        affected.add(endpoint);
        if (old != null) detach(endpoint, old);
        groups.putIfAbsent(key, next);
        next.members.add(endpoint);
        endpoints.put(endpoint, next);
        return new Change(affected);
    }
    /** Removes one loaded endpoint; absent removal is a no-op. No callback runs during the update. */
    public Change remove(UUID endpoint) {
        check();
        Objects.requireNonNull(endpoint);
        var old = endpoints.get(endpoint);
        if (old == null) return new Change(Set.of());
        var affected = new HashSet<>(old.members);
        detach(endpoint, old);
        return new Change(affected);
    }

    private void detach(UUID endpoint, Group old) {
        endpoints.remove(endpoint);
        old.members.remove(endpoint);
        if (old.members.isEmpty()) groups.remove(old.key);
    }
    /** Rechecks exact current membership and uniqueness; this does not replace owner, host or AE-active checks. */
    public boolean permits(UUID endpoint, Object grid, UUID domain) {
        check();
        var group = endpoints.get(endpoint);
        return group != null && group.key.grid == grid && group.key.domain.equals(domain) && group.members.size() == 1;
    }

    public int count(Object grid, UUID domain) {
        check();
        var group = groups.get(new Key(grid, domain));
        return group == null ? 0 : group.members.size();
    }

    public int size() {
        check();
        return endpoints.size();
    }

    public void clear() {
        check();
        endpoints.clear();
        groups.clear();
    }

    private void check() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Interface membership accessed off owner thread");
    }
}
