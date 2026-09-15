// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.storage;

import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * One activated domain, owned by its constructing server thread. Complete bucket validation precedes construction.
 * Bucket objects remain native SavedData-owned; this ledger retains references, immutable keys and in-memory
 * quantities/sequence indexes until domain disposal. Admission limits occupied keys including reservations;
 * there is no secondary management-collection quota or cross-domain cache.
 * Reads and capacity simulations never mutate. All transactions are synchronous and must close in their starting
 * tick; this class performs no external calls, permission checks, disk writes or automatic adapter interpretation.
 */
public final class DomainLedger {
    /** Immutable cursor snapshot; sequence is runtime-only and quantity may change before subsequent execution. */
    public record Cursor(long sequence, ResourceVariantKey key, long amount) {}

    private final Thread owner = Thread.currentThread();
    private final UUID networkId;
    private final IntFunction<StorageBucketData> createBucket;
    private final StorageBucketData[] buckets = new StorageBucketData[StorageBucketHash.COUNT];
    private final Map<ResourceVariantKey, Entry> entries = new HashMap<>();
    private final TreeMap<Long, Entry> visible = new TreeMap<>();
    private long lastSequence;
    private long revision;
    private long reservedRevisions;
    private @org.jetbrains.annotations.Nullable ChangeJournal changes;
    private int reservations;
    private boolean available = true;
    private @org.jetbrains.annotations.Nullable java.util.function.Consumer<ResourceVariantKey> retired;

    /** Immutable absolute inventory event; zero removes the runtime ID and revisions are monotonically increasing. */
    public record Change(long sequence, ResourceVariantKey key, long amount, long revision) {}

    /** Owner-thread nonmutating revision query. Reservations and simulations do not advance it. */
    public long revision() {
        checkThread();
        return revision;
    }

    /**
     * Opens the sole owner-thread publisher journal before a snapshot ceiling is read. The publisher fans out
     * changes to its receivers; competing publishers reject without mutation. Closing releases all pending keys.
     * Only 1..65536 unique unsent IDs may be retained; overflow fails the journal without affecting inventory.
     */
    public ChangeJournal openChanges(int maximumEntries) {
        checkThread();
        if (maximumEntries < 1 || maximumEntries > 65536) throw new IllegalArgumentException("Invalid journal bound");
        if (changes != null) throw new IllegalStateException("Domain already has a change publisher");
        changes = new ChangeJournal(maximumEntries);
        return changes;
    }

    /** Owner-thread O(log n) live-ID lookup; retired IDs return empty and unavailable ledgers throw before lookup. */
    public Optional<Cursor> findSequence(long sequence) {
        checkThread();
        if (sequence <= 0) return Optional.empty();
        Entry entry = visible.get(sequence);
        return entry == null ? Optional.empty() : Optional.of(new Cursor(entry.sequence, entry.key, entry.amount));
    }

    /**
     * Owner-thread finite snapshot scan: returns the next live ID in (after, ceiling], without wrapping, copying
     * a complete map, or changing cursors. Quantities are current reads; subscribe before fixing the ceiling.
     */
    public Optional<Cursor> nextWithin(long after, long ceiling) {
        checkThread();
        if (after < 0 || ceiling < 0 || ceiling > lastSequence)
            throw new IllegalArgumentException("Invalid scan fence");
        Map.Entry<Long, Entry> next = visible.higherEntry(after);
        if (next == null || next.getKey() > ceiling) return Optional.empty();
        Entry entry = next.getValue();
        return Optional.of(new Cursor(entry.sequence, entry.key, entry.amount));
    }

    /**
     * Ledger-owned bounded absolute-change queue. All methods require the owner thread. Polling consumes one
     * event, never inventory; failed/closed journals reject polling and must be replaced by explicit resync.
     * Repeated changes retain only the latest amount for that ID. There is no idle history or retry queue.
     */
    public final class ChangeJournal implements AutoCloseable {
        private int maximumEntries;
        private final java.util.LinkedHashMap<Long, Change> pending = new java.util.LinkedHashMap<>();
        private boolean failed;
        private boolean closed;

        private ChangeJournal(int maximumEntries) {
            this.maximumEntries = maximumEntries;
        }

        /** Applies a new owner-thread limit; reducing below pending IDs fails rather than dropping updates. */
        public void limit(int maximumEntries) {
            checkOwner();
            if (maximumEntries < 1 || maximumEntries > 65536)
                throw new IllegalArgumentException("Invalid journal bound");
            this.maximumEntries = maximumEntries;
            if (pending.size() > maximumEntries) fail();
        }

        private void record(Entry entry) {
            if (closed || failed) return;
            if (!pending.containsKey(entry.sequence) && pending.size() >= maximumEntries) {
                fail();
                return;
            }
            pending.put(entry.sequence, new Change(entry.sequence, entry.key, entry.amount, revision));
        }

        private void fail() {
            failed = true;
            pending.clear();
        }

        /** Owner-thread status read; failure is sticky until this journal is closed and a new one is opened. */
        public boolean failed() {
            checkOwner();
            return failed || closed || !available;
        }

        /** Owner-thread O(1) count of retained IDs; does not consume events or inventory. */
        public int pendingCount() {
            checkOwner();
            return pending.size();
        }

        /** Consumes the oldest coalesced slot on the owner thread, or rejects a failed/closed journal. */
        public Optional<Change> poll() {
            checkOwner();
            if (failed()) throw new IllegalStateException("Domain changes are unavailable");
            if (pending.isEmpty()) return Optional.empty();
            var first = pending.pollFirstEntry();
            return Optional.of(first.getValue());
        }

        @Override
        public void close() {
            checkOwner();
            if (closed) return;
            closed = true;
            pending.clear();
            if (changes == this) changes = null;
        }
    }

    /** Installs/releases the single owner-thread runtime retirement observer. It must only evict derived state.
     * Notifications follow final ownership release, never a transient zero while a return remains reserved. */
    public void onRetired(
            @org.jetbrains.annotations.Nullable java.util.function.Consumer<ResourceVariantKey> observer) {
        checkOwner();
        retired = observer;
    }

    /** Runtime-only sequence ceiling for finite scans; queries never allocate or advance a cursor. */
    public long sequenceCeiling() {
        checkThread();
        return lastSequence;
    }

    /** Runtime sequence for a currently visible key, or zero; no mutation or simulation side effects. */
    public long sequence(ResourceVariantKey key) {
        checkThread();
        Entry entry = entries.get(Objects.requireNonNull(key));
        return entry == null || entry.amount == 0 ? 0 : entry.sequence;
    }

    /**
     * Activates validated buckets without dirtying or copying quantities on later updates. Constructor failures
     * do not mutate supplied shards. The factory must synchronously register a new empty matching shard before
     * returning; it is called only by execution reservation, never simulation. Registration failure propagates
     * before a deposit is authorized, so callers must not extract any resource before obtaining the reservation.
     */
    public DomainLedger(
            UUID networkId, Map<Integer, StorageBucketData> loaded, IntFunction<StorageBucketData> createBucket) {
        this.networkId = Objects.requireNonNull(networkId);
        this.createBucket = Objects.requireNonNull(createBucket);
        Objects.requireNonNull(loaded);
        for (Map.Entry<Integer, StorageBucketData> row : loaded.entrySet()) {
            int index = row.getKey();
            StorageBucketData bucket = Objects.requireNonNull(row.getValue());
            validateBucket(index, bucket);
            buckets[index] = bucket;
            for (Map.Entry<ResourceVariantKey, Long> resource :
                    bucket.snapshot().entrySet()) {
                Entry entry = new Entry(resource.getKey(), bucket, nextSequence());
                entry.amount = resource.getValue();
                if (entries.putIfAbsent(entry.key, entry) != null)
                    throw new IllegalArgumentException("Duplicate domain resource");
                visible.put(entry.sequence, entry);
            }
        }
    }

    /** O(1) nonmutating quantity query on the owner thread; reservations are never exposed as inventory. */
    public long amount(ResourceVariantKey key) {
        checkThread();
        Entry entry = entries.get(Objects.requireNonNull(key));
        return entry == null ? 0 : entry.amount;
    }

    /** Positive inventory count, without mutation, on the owner thread. */
    public int variantCount() {
        checkThread();
        return visible.size();
    }

    /** O(1) guard for domain disposal/deletion; in-flight resources must retain their original domain. */
    public boolean hasReservations() {
        checkThread();
        return reservations != 0;
    }

    /**
     * Pure owner-thread simulation of accepted capacity, including all short-lived deposits and return capacity.
     * A limit of -1 disables the gameplay key quota; zero denies new keys. Lower limits never erase existing keys.
     * Invalid limits throw without mutation; no bucket is loaded or created and no sequence is assigned.
     */
    public long insertCapacity(ResourceVariantKey key, long variantLimit) {
        checkThread();
        Objects.requireNonNull(key);
        if (variantLimit < -1) throw new IllegalArgumentException("Invalid domain variant limit");
        Entry entry = entries.get(key);
        if (entry == null) return variantLimit >= 0 && entries.size() >= variantLimit ? 0 : Long.MAX_VALUE;
        return Long.MAX_VALUE - entry.amount - entry.incoming - entry.returnable;
    }

    /**
     * Reserves exact positive insertion capacity before external extraction, on the owner thread. Rejection
     * leaves all state unchanged; success may create an empty bucket but does not add inventory. Existing-key
     * admission is O(1); a newly occupied key hashes once per admission and joins the sequence index only on commit.
     */
    public Optional<Deposit> reserveDeposit(ResourceVariantKey key, long amount, long variantLimit) {
        checkThread();
        if (amount <= 0) throw new IllegalArgumentException("Nonpositive domain deposit");
        if (insertCapacity(key, variantLimit) < amount
                || reservations == Integer.MAX_VALUE
                || revision > Long.MAX_VALUE - reservedRevisions - 1) return Optional.empty();
        Entry entry = entries.get(key);
        if (entry == null) {
            if (lastSequence == Long.MAX_VALUE) throw new IllegalStateException("Domain runtime sequence exhausted");
            int index = StorageBucketHash.bucket(key);
            StorageBucketData bucket = buckets[index];
            if (bucket == null) {
                bucket = Objects.requireNonNull(createBucket.apply(index));
                validateBucket(index, bucket);
                if (bucket.variantCount() != 0) throw new IllegalStateException("New storage bucket is not empty");
                buckets[index] = bucket;
            }
            entry = new Entry(key, bucket, nextSequence());
            entries.put(key, entry);
        }
        reservedRevisions++;
        entry.incoming += amount;
        entry.reservations++;
        reservations++;
        return Optional.of(new Deposit(entry, amount));
    }

    /**
     * Removes an exact positive amount and reserves its return capacity before external insertion. Insufficient
     * inventory rejects without mutation. The returned handle must settle only a proven remainder; an unknown
     * external outcome is never inferred by this ledger. Quantity updates dirty only the matching bucket.
     */
    public Optional<Withdrawal> withdraw(ResourceVariantKey key, long amount) {
        checkThread();
        Objects.requireNonNull(key);
        if (amount <= 0) throw new IllegalArgumentException("Nonpositive domain withdrawal");
        Entry entry = entries.get(key);
        if (entry == null
                || entry.amount < amount
                || reservations == Integer.MAX_VALUE
                || revision > Long.MAX_VALUE - reservedRevisions - 2) return Optional.empty();
        reservedRevisions++;
        setAmount(entry, entry.amount - amount);
        entry.returnable += amount;
        entry.reservations++;
        reservations++;
        return Optional.of(new Withdrawal(entry, amount));
    }

    /**
     * Returns the next positive resource after a runtime sequence, wrapping once. O(log n), without moving a
     * cursor or mutating state; the scheduler alone owns its continuation. Negative sequences reject.
     */
    public Optional<Cursor> nextAfter(long sequence) {
        checkThread();
        if (sequence < 0) throw new IllegalArgumentException("Negative domain sequence");
        Map.Entry<Long, Entry> next = visible.higherEntry(sequence);
        if (next == null) next = visible.firstEntry();
        if (next == null) return Optional.empty();
        Entry entry = next.getValue();
        return Optional.of(new Cursor(entry.sequence, entry.key, entry.amount));
    }

    private void setAmount(Entry entry, long amount) {
        if (entry.amount == amount) return;
        long nextRevision = Math.incrementExact(revision);
        entry.bucket.setAmount(entry.key, amount);
        if (entry.amount == 0 && amount > 0) visible.put(entry.sequence, entry);
        else if (entry.amount > 0 && amount == 0) visible.remove(entry.sequence);
        entry.amount = amount;
        revision = nextRevision;
        if (changes != null) changes.record(entry);
    }

    private void release(Entry entry) {
        entry.reservations--;
        reservations--;
        if (entry.amount == 0 && entry.reservations == 0) {
            entries.remove(entry.key);
            if (retired != null) {
                try {
                    retired.accept(entry.key);
                } catch (RuntimeException observerFailure) {
                    org.slf4j.LoggerFactory.getLogger(DomainLedger.class)
                            .error(
                                    "Domain runtime retirement observer failed after settled ownership",
                                    observerFailure);
                }
            }
        }
    }

    private long nextSequence() {
        if (lastSequence == Long.MAX_VALUE) throw new IllegalStateException("Domain runtime sequence exhausted");
        return ++lastSequence;
    }

    private void validateBucket(int index, StorageBucketData bucket) {
        if (index < 0
                || index >= buckets.length
                || index != bucket.bucketIndex()
                || !networkId.equals(bucket.networkId())) {
            throw new IllegalArgumentException("Storage bucket identity does not match domain");
        }
    }

    /** Disables subsequent access after a storage failure; outstanding handles may only release capacity. */
    public void invalidate() {
        checkOwner();
        available = false;
        if (changes != null) changes.fail();
    }

    /** Owner-thread lifecycle query, usable after invalidation; does not inspect quantities or mutate state. */
    public boolean isAvailable() {
        checkOwner();
        return available;
    }

    private void checkThread() {
        checkOwner();
        if (!available) throw new IllegalStateException("Domain ledger is unavailable");
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Domain ledger accessed off owner thread");
    }

    private static final class Entry {
        private final ResourceVariantKey key;
        private final StorageBucketData bucket;
        private final long sequence;
        private long amount;
        private long incoming;
        private long returnable;
        private int reservations;

        private Entry(ResourceVariantKey key, StorageBucketData bucket, long sequence) {
            this.key = key;
            this.bucket = bucket;
            this.sequence = sequence;
        }
    }

    /** Owner-thread execution capacity, single-use commit with idempotent cancellation; never a simulation. */
    public final class Deposit implements AutoCloseable {
        private final Entry entry;
        private final long reserved;
        private boolean closed;

        private Deposit(Entry entry, long reserved) {
            this.entry = entry;
            this.reserved = reserved;
        }

        /** Credits a proven 0..reserved amount; invalid input leaves this reservation usable and inventory intact. */
        public void commit(long amount) {
            checkThread();
            if (closed) throw new IllegalStateException("Closed domain deposit");
            if (amount < 0 || amount > reserved) throw new IllegalArgumentException("Invalid committed domain amount");
            setAmount(entry, entry.amount + amount);
            close();
        }

        /** Cancels unused capacity on the owner thread, with no resource or dirty-state change. */
        @Override
        public void close() {
            checkOwner();
            if (closed) return;
            entry.incoming -= reserved;
            reservedRevisions--;
            release(entry);
            closed = true;
        }
    }

    /** Owner-thread withdrawn resource ownership; close releases unused return capacity without inventing a refund. */
    public final class Withdrawal implements AutoCloseable {
        private final Entry entry;
        private final long taken;
        private boolean closed;

        private Withdrawal(Entry entry, long taken) {
            this.entry = entry;
            this.taken = taken;
        }

        /** Returns the proven 0..taken remainder, bypassing new-key quotas using previously reserved capacity. */
        public void returnRemainder(long amount) {
            checkThread();
            if (closed) throw new IllegalStateException("Closed domain withdrawal");
            if (amount < 0 || amount > taken) throw new IllegalArgumentException("Invalid domain remainder");
            setAmount(entry, entry.amount + amount);
            close();
        }

        /** Ends ownership on the owner thread; idempotent and does not infer unknown external results. */
        @Override
        public void close() {
            checkOwner();
            if (closed) return;
            entry.returnable -= taken;
            reservedRevisions--;
            release(entry);
            closed = true;
        }
    }
}
