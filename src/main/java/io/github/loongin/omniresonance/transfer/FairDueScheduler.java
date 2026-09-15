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
    private final LinkedHashMap<UUID, Ready<K>> ready = new LinkedHashMap<>();
    private long sequence, examined;

    public void schedule(K key, UUID networkId, long dueTick) {
        schedule(key, networkId, dueTick, false);
    }

    /** Schedules bounded recovery work after currently ready ordinary work within its network, without I/O. */
    public void scheduleLowPriority(K key, UUID networkId, long dueTick) {
        schedule(key, networkId, dueTick, true);
    }

    private void schedule(K key, UUID networkId, long dueTick, boolean lowPriority) {
        Entry<K> old = entries.get(key);
        if (old != null && old.dueTick == dueTick && old.networkId.equals(networkId) && old.lowPriority == lowPriority)
            return;
        remove(key);
        Entry<K> entry = new Entry<>(key, networkId, dueTick, sequence++, lowPriority);
        entries.put(key, entry);
        due.add(entry);
        compact();
    }

    public void remove(K key) {
        Entry<K> old = entries.remove(key);
        if (old != null && old.ready) {
            Ready<K> queue = ready.get(old.networkId);
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
            ready.computeIfAbsent(e.networkId, ignored -> new Ready<>()).add(e);
        }
        if (ready.isEmpty()) return null;
        var network = ready.entrySet().iterator().next();
        UUID id = network.getKey();
        Ready<K> queue = network.getValue();
        ready.remove(id);
        Entry<K> e = queue.poll();
        if (!queue.isEmpty()) ready.put(id, queue);
        entries.remove(e.key);
        return e.key;
    }

    public int queuedEntries() {
        return due.size() + ready.values().stream().mapToInt(Ready::size).sum();
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
        final boolean lowPriority;

        Entry(K key, UUID networkId, long dueTick, long sequence, boolean lowPriority) {
            this.key = key;
            this.networkId = networkId;
            this.dueTick = dueTick;
            this.sequence = sequence;
            this.lowPriority = lowPriority;
        }
    }

    private static final class Ready<K> {
        private final LinkedHashSet<Entry<K>> ordinary = new LinkedHashSet<>();
        private final LinkedHashSet<Entry<K>> recovery = new LinkedHashSet<>();

        private void add(Entry<K> entry) {
            (entry.lowPriority ? recovery : ordinary).add(entry);
        }

        private void remove(Entry<K> entry) {
            (entry.lowPriority ? recovery : ordinary).remove(entry);
        }

        private Entry<K> poll() {
            LinkedHashSet<Entry<K>> selected = ordinary.isEmpty() ? recovery : ordinary;
            Entry<K> entry = selected.iterator().next();
            selected.remove(entry);
            return entry;
        }

        private boolean isEmpty() {
            return ordinary.isEmpty() && recovery.isEmpty();
        }

        private int size() {
            return ordinary.size() + recovery.size();
        }
    }
}
