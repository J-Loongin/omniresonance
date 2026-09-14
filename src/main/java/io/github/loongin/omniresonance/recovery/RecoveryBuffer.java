// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.recovery;

import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Constructing-thread-owned recovery occupancy. Keys live until restore or reservation release;
 * admission bounds the union of saved entries and active reservations. No world objects are retained.
 */
public final class RecoveryBuffer {
    /** Named NBT fields type_id, canonical_bytes, amount, including the compound end marker. */
    public static final int ENTRY_OVERHEAD_BYTES = 52;

    private final Thread owner = Thread.currentThread();
    private final Runnable onChanged;
    private Map<ResourceVariantKey, Entry> entries = new HashMap<>();
    private int savedVariants;
    private long savedBytes;
    private long occupiedBytes;
    private int reservations;

    public RecoveryBuffer(Runnable onChanged) {
        this.onChanged = Objects.requireNonNull(onChanged);
    }

    /**
     * Reserves possible remainder on the owner thread. This is an execution operation, never simulation.
     * Rejection changes nothing; successful reservation does not dirty persistent state.
     */
    public Optional<Reservation> reserve(ResourceVariantKey key, long amount, int maxVariants, long maxBytes) {
        checkThread();
        Objects.requireNonNull(key);
        if (amount <= 0 || maxVariants < 0 || maxBytes < 0) {
            throw new IllegalArgumentException("Invalid recovery reservation");
        }
        Entry entry = entries.get(key);
        long charge = entry == null ? charge(key) : 0;
        if (entries.size() > maxVariants
                || occupiedBytes > maxBytes
                || entry == null && entries.size() == maxVariants
                || charge > maxBytes - occupiedBytes) {
            return Optional.empty();
        }
        if (entry != null && amount > Long.MAX_VALUE - entry.saved - entry.reserved) {
            return Optional.empty();
        }
        if (reservations == Integer.MAX_VALUE) {
            return Optional.empty();
        }
        if (entry == null) {
            entry = new Entry();
            entries.put(key, entry);
            occupiedBytes += charge;
        }
        entry.reserved += amount;
        reservations++;
        return Optional.of(new Reservation(key, entry, amount));
    }

    public long amount(ResourceVariantKey key) {
        checkThread();
        Entry entry = entries.get(Objects.requireNonNull(key));
        return entry == null ? 0 : entry.saved;
    }

    /** Owner-thread O(1) admission guard for deleting the original recovery owner; no simulation or mutation. */
    public boolean hasActiveReservations() {
        checkThread();
        return reservations != 0;
    }

    public boolean isEmpty() {
        checkThread();
        return savedVariants == 0;
    }

    /** Saved compound-entry bytes; excludes the enclosing named list header and its shared framing. */
    public long encodedBytes() {
        checkThread();
        return savedBytes;
    }

    public int variantCount() {
        checkThread();
        return savedVariants;
    }

    /** Validates all entries before atomic replacement, preserving unknown raw keys. Does not dirty. */
    public void restore(Map<ResourceVariantKey, Long> data) {
        checkThread();
        Objects.requireNonNull(data);
        if (reservations != 0) {
            throw new IllegalStateException("Cannot restore with active reservations");
        }
        Map<ResourceVariantKey, Entry> replacement = new HashMap<>();
        long bytes = 0;
        for (Map.Entry<ResourceVariantKey, Long> record : data.entrySet()) {
            ResourceVariantKey key = Objects.requireNonNull(record.getKey());
            long amount = Objects.requireNonNull(record.getValue());
            if (amount < 0) {
                throw new IllegalArgumentException("Negative recovery amount");
            }
            if (amount == 0) {
                continue;
            }
            Entry entry = new Entry();
            entry.saved = amount;
            if (replacement.putIfAbsent(key, entry) != null) {
                throw new IllegalArgumentException("Duplicate recovery key");
            }
            bytes = Math.addExact(bytes, charge(key));
        }
        entries = replacement;
        savedVariants = replacement.size();
        savedBytes = bytes;
        occupiedBytes = bytes;
    }

    /** Detached save snapshot; O(number of variants), for persistence outside the transfer hot path. */
    public Map<ResourceVariantKey, Long> snapshot() {
        checkThread();
        Map<ResourceVariantKey, Long> snapshot = new HashMap<>();
        for (Map.Entry<ResourceVariantKey, Entry> record : entries.entrySet()) {
            if (record.getValue().saved > 0) {
                snapshot.put(record.getKey(), record.getValue().saved);
            }
        }
        return snapshot;
    }

    private static long charge(ResourceVariantKey key) {
        return (long) key.encodedSizeBytes() + ENTRY_OVERHEAD_BYTES;
    }

    private void checkThread() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Recovery buffer accessed off owner thread");
        }
    }

    private static final class Entry {
        private long saved;
        private long reserved;
    }

    /** Owned, single-use execution reservation. Close is idempotent; invalid commits preserve the reservation. */
    public final class Reservation implements AutoCloseable {
        private final ResourceVariantKey key;
        private final Entry entry;
        private final long amount;
        private boolean closed;

        private Reservation(ResourceVariantKey key, Entry entry, long amount) {
            this.key = key;
            this.entry = entry;
            this.amount = amount;
        }

        /** Commits only a known remainder within the reserved amount, releases unused capacity, then dirties once. */
        public void commit(long remainder) {
            checkThread();
            if (closed) {
                throw new IllegalStateException("Recovery reservation already consumed");
            }
            if (remainder < 0 || remainder > amount) {
                throw new IllegalArgumentException("Invalid recovery remainder");
            }
            if (remainder > 0) {
                if (entry.saved == 0) {
                    savedVariants++;
                    savedBytes += charge(key);
                }
                entry.saved += remainder;
            }
            close();
            if (remainder > 0) {
                onChanged.run();
            }
        }

        @Override
        public void close() {
            checkThread();
            if (closed) {
                return;
            }
            closed = true;
            entry.reserved -= amount;
            reservations--;
            if (entry.saved == 0 && entry.reserved == 0) {
                entries.remove(key);
                occupiedBytes -= charge(key);
            }
        }
    }
}
