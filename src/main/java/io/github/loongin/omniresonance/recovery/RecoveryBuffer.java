// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.recovery;

import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * Constructing-thread-owned recovery occupancy. Keys live until restore or reservation release;
 * admission bounds the union of saved entries and active reservations. No world objects are retained.
 */
public final class RecoveryBuffer {
    /** Trusted internal, owner-thread destination; returns proven acceptance and makes no external capability calls.
     * Implementations must handle failed admission as zero before any quantity mutation; this is not a mod adapter API. */
    @FunctionalInterface
    public interface DomainReturn {
        long insert(ResourceVariantKey key, long amount);
    }

    /** Exact disposition of a known remainder; unknown modifying results are not represented by this value. */
    public record Placement(long stored, long buffered) {}

    private @Nullable DomainReturn domainReturn;

    /** Installs/removes the original-network runtime destination without resource, dirty-state or simulation effects. */
    public void onDomainReturn(@Nullable DomainReturn destination) {
        checkThread();
        domainReturn = destination;
    }
    /** Named NBT fields type_id, canonical_bytes, amount, including the compound end marker. */
    public static final int ENTRY_OVERHEAD_BYTES = 52;

    private final Thread owner = Thread.currentThread();
    private final Runnable onChanged;
    private Map<ResourceVariantKey, Entry> entries = new HashMap<>();
    /** Positive saved keys only, owned by this buffer; O(1) rotation, removed at zero, rebuilt on restore. */
    private LinkedHashSet<ResourceVariantKey> drainQueue = new LinkedHashSet<>();

    private boolean draining;
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
        if (reservations != 0 || draining) {
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
        drainQueue = new LinkedHashSet<>(replacement.keySet());
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

    /**
     * Executes one owner-thread recovery step into an already activated original-domain ledger. Examines at
     * most one positive key and rotates blocked keys in O(1), without native calls or per-quantity iteration.
     * Reserves destination capacity before crediting; only proven credited quantity is removed here. Capacity
     * rejection changes only the runtime cursor, not inventory/dirty state. Destination failures propagate
     * with buffer inventory preserved. Recursive drain/restore reject; no resource is held across ticks.
     */
    public long drainOne(io.github.loongin.omniresonance.storage.DomainLedger ledger, long variantLimit) {
        checkThread();
        Objects.requireNonNull(ledger);
        if (variantLimit < -1) throw new IllegalArgumentException("Invalid storage variant limit");
        if (draining) throw new IllegalStateException("Recursive recovery drain");
        if (drainQueue.isEmpty()) return 0;
        ResourceVariantKey key = drainQueue.iterator().next();
        drainQueue.remove(key);
        drainQueue.add(key);
        Entry entry = entries.get(key);
        draining = true;
        try {
            long amount = Math.min(entry.saved, ledger.insertCapacity(key, variantLimit));
            if (amount == 0) return 0;
            try (var deposit = ledger.reserveDeposit(key, amount, variantLimit).orElse(null)) {
                if (deposit == null) return 0;
                deposit.commit(amount);
            }
            entry.saved -= amount;
            if (entry.saved == 0) {
                drainQueue.remove(key);
                savedVariants--;
                long bytes = charge(key);
                savedBytes -= bytes;
                if (entry.reserved == 0) {
                    entries.remove(key);
                    occupiedBytes -= bytes;
                }
            }
            onChanged.run();
            return amount;
        } finally {
            draining = false;
        }
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
        private boolean placing;

        private Reservation(ResourceVariantKey key, Entry entry, long amount) {
            this.key = key;
            this.entry = entry;
            this.amount = amount;
        }

        /** Places a known remainder in the original domain first, then commits only its rest to this reservation.
         * Zero never activates storage. Invalid input rejects before destination access; this synchronous owner-thread
         * execution retains all originally reserved buffer capacity until the destination has confirmed acceptance. */
        public Placement placeKnownRemainder(long remainder) {
            checkThread();
            if (closed || placing) throw new IllegalStateException("Recovery placement already consumed or active");
            if (remainder < 0 || remainder > amount) throw new IllegalArgumentException("Invalid recovery remainder");
            placing = true;
            try {
                long stored = remainder == 0 || domainReturn == null ? 0 : domainReturn.insert(key, remainder);
                if (stored < 0 || stored > remainder)
                    throw new IllegalStateException("Invalid trusted domain acceptance");
                long buffered = remainder - stored;
                commit(buffered);
                return new Placement(stored, buffered);
            } finally {
                placing = false;
            }
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
                    drainQueue.add(key);
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
