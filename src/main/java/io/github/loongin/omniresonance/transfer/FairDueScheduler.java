// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread-owned due heap and active-network ring. Keys exist only while scheduled; removal is O(1)
 * outside the heap. Stale heap entries compact above twice the live population plus 64, bounding wakeup churn.
 * Sleeping ticks inspect only the heap head. Active queues contain each configuration at most once.
 */
public final class FairDueScheduler<K> {
    private final Map<K, Entry<K>> entries = new HashMap<>();
    private final PriorityQueue<Entry<K>> due = new PriorityQueue<>(
            Comparator.<Entry<K>>comparingLong(e -> e.dueTick).thenComparingLong(e -> e.sequence));
    private final LinkedHashMap<UUID, LinkedHashSet<Entry<K>>> ready = new LinkedHashMap<>();
    private long sequence, examined;

    public void schedule(K key, UUID networkId, long dueTick) {
        Entry<K> old = entries.get(key);
        if (old != null && old.dueTick == dueTick && old.networkId.equals(networkId)) return;
        remove(key);
        Entry<K> entry = new Entry<>(key, networkId, dueTick, sequence++);
        entries.put(key, entry);
        due.add(entry);
        compact();
    }

    public void remove(K key) {
        Entry<K> old = entries.remove(key);
        if (old != null && old.ready) {
            LinkedHashSet<Entry<K>> queue = ready.get(old.networkId);
            queue.remove(old);
            if (queue.isEmpty()) ready.remove(old.networkId);
        }
        compact();
    }

    public @Nullable K poll(long currentTick) {
        return poll(currentTick, () -> true);
    }

    public @Nullable K poll(long currentTick, java.util.function.BooleanSupplier canContinue) {
        while (!due.isEmpty() && due.peek().dueTick <= currentTick && canContinue.getAsBoolean()) {
            Entry<K> e = due.remove();
            examined++;
            if (entries.get(e.key) != e) continue;
            e.ready = true;
            ready.computeIfAbsent(e.networkId, ignored -> new LinkedHashSet<>()).add(e);
        }
        if (ready.isEmpty()) return null;
        var network = ready.entrySet().iterator().next();
        UUID id = network.getKey();
        LinkedHashSet<Entry<K>> queue = network.getValue();
        ready.remove(id);
        Entry<K> e = queue.iterator().next();
        queue.remove(e);
        if (!queue.isEmpty()) ready.put(id, queue);
        entries.remove(e.key);
        return e.key;
    }

    public int queuedEntries() {
        return due.size()
                + ready.values().stream().mapToInt(java.util.Set::size).sum();
    }

    public long examinedEntries() {
        return examined;
    }

    public void clear() {
        entries.clear();
        due.clear();
        ready.clear();
    }

    private void compact() {
        if (due.size() <= 2L * entries.size() + 64) return;
        due.clear();
        for (Entry<K> e : entries.values()) if (!e.ready) due.add(e);
    }

    private static final class Entry<K> {
        final K key;
        final UUID networkId;
        final long dueTick, sequence;
        boolean ready;

        Entry(K key, UUID networkId, long dueTick, long sequence) {
            this.key = key;
            this.networkId = networkId;
            this.dueTick = dueTick;
            this.sequence = sequence;
        }
    }
}
