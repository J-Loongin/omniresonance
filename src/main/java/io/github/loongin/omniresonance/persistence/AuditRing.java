// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/** Shard-owned server-thread ring. No I/O, simulation, counters, clocks, or authority are consulted. */
final class AuditRing {
    static final int MAXIMUM = 10000;
    private final ArrayDeque<AuditEntry> entries = new ArrayDeque<>();

    int size() {
        return entries.size();
    }

    List<AuditEntry> snapshot() {
        return List.copyOf(entries);
    }

    boolean append(AuditEntry entry, int capacity) {
        Objects.requireNonNull(entry);
        if (capacity < 0 || capacity > MAXIMUM) throw new IllegalArgumentException("Invalid audit capacity");
        if (capacity == 0) return false;
        while (entries.size() >= capacity) entries.removeFirst();
        entries.addLast(entry);
        return true;
    }

    void restore(List<AuditEntry> values) {
        var safe = List.copyOf(values);
        if (safe.size() > MAXIMUM) throw new IllegalArgumentException("Oversize audit ring");
        entries.clear();
        entries.addAll(safe);
    }
}
