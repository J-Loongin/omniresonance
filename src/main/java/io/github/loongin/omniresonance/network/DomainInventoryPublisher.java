// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.networking.DomainInventoryFrame;
import io.github.loongin.omniresonance.networking.DomainInventoryRecordCodec;
import io.github.loongin.omniresonance.storage.DomainLedger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One server-thread network publisher, opened only after access checks. All full receivers share one finite base
 * list and bounded catchup log. Live receivers own only bounded unsent deltas and one bounded record fragment.
 * No full mirror copies, ACKs, disk I/O, world queries, or implicit retries. Callers must enqueue each returned
 * frame immediately on the same thread so End seals against the authoritative count/revision atomically.
 */
public final class DomainInventoryPublisher implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final DomainLedger ledger;
    private DomainLedger.ChangeJournal journal;
    private final Map<UUID, Receiver> receivers = new HashMap<>();
    private @Nullable Snapshot snapshot;
    private int limit;
    private boolean buildTurn = true;
    private boolean closed;

    public DomainInventoryPublisher(DomainLedger ledger, int limit) {
        this.ledger = Objects.requireNonNull(ledger);
        this.limit = limit;
        journal = ledger.openChanges(limit);
    }

    /** Adds an authorized receiver. The global coordinator owns admission limits and duplicate-player removal. */
    public Receiver subscribe(UUID player, UUID session, long generation) {
        check();
        if (generation <= 0 || receivers.containsKey(player))
            throw new IllegalArgumentException("Duplicate or invalid receiver");
        if (journal.failed()) {
            for (Receiver receiver : receivers.values())
                if (receiver.failure == null) throw new IllegalStateException("Publisher failed");
            journal.close();
            journal = ledger.openChanges(limit);
        }
        if (snapshot == null)
            snapshot = new Snapshot(ledger.sequenceCeiling(), ledger.revision(), ledger.variantCount());
        var receiver =
                new Receiver(Objects.requireNonNull(player), Objects.requireNonNull(session), generation, snapshot);
        receivers.put(player, receiver);
        return receiver;
    }

    /** Applies live queue bounds; an overflowing stream fails without touching domain inventory. */
    public void limit(int limit) {
        check();
        journal.limit(limit);
        this.limit = limit;
        if (snapshot != null && snapshot.byId.size() > limit) failSnapshot();
        for (Receiver receiver : receivers.values())
            if (receiver.pendingCount() > limit) receiver.fail(DomainInventoryFrame.Reason.CHANGING_TOO_FAST);
    }

    /** At most one base read or coalesced-change fanout; true means bounded work advanced. */
    public boolean work() {
        check();
        if (!ledger.isAvailable() || journal.failed()) {
            var reason = ledger.isAvailable()
                    ? DomainInventoryFrame.Reason.CHANGING_TOO_FAST
                    : DomainInventoryFrame.Reason.UNAVAILABLE;
            for (Receiver receiver : receivers.values()) receiver.fail(reason);
            releaseSnapshot();
            return false;
        }
        if (buildTurn && snapshot != null && !snapshot.built) {
            buildTurn = false;
            return build();
        }
        var change = journal.poll();
        if (change.isPresent()) {
            var value = change.orElseThrow();
            if (snapshot != null && value.revision() > snapshot.startRevision && !snapshot.record(value))
                failSnapshot();
            for (Receiver receiver : receivers.values())
                if (!receiver.full && receiver.failure == null && !receiver.closed) receiver.record(value);
            buildTurn = true;
            return true;
        }
        if (snapshot != null && !snapshot.built) return build();
        return false;
    }

    private boolean build() {
        var current = Objects.requireNonNull(snapshot);
        var next = ledger.nextWithin(current.cursor, current.ceiling);
        if (next.isEmpty()) current.built = true;
        else {
            var entry = next.orElseThrow();
            current.base.add(entry);
            current.cursor = entry.sequence();
        }
        return true;
    }

    private void failSnapshot() {
        for (Receiver receiver : receivers.values())
            if (receiver.full) receiver.fail(DomainInventoryFrame.Reason.CHANGING_TOO_FAST);
        releaseSnapshot();
    }

    private void releaseSnapshot() {
        snapshot = null;
    }

    private void retireSnapshotIfUnused() {
        for (Receiver receiver : receivers.values())
            if (receiver.full && !receiver.closed && receiver.failure == null) return;
        releaseSnapshot();
    }

    /** Server-thread diagnostics without copying retained data. */
    public int baseRecords() {
        check();
        return snapshot == null ? 0 : snapshot.base.size();
    }

    public int receiverCount() {
        check();
        return receivers.size();
    }

    public boolean hasSnapshot() {
        check();
        return snapshot != null;
    }

    @Override
    public void close() {
        checkThread();
        if (closed) return;
        for (Receiver receiver : receivers.values()) {
            receiver.closed = true;
            receiver.clear();
        }
        receivers.clear();
        releaseSnapshot();
        journal.close();
        closed = true;
    }

    private void checkThread() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Inventory publisher accessed off owner thread");
    }

    private void check() {
        if (Thread.currentThread() != owner || closed)
            throw new IllegalStateException("Inactive or off-thread inventory publisher");
    }

    private final class Snapshot {
        final long ceiling, startRevision;
        final int initialCount;
        final ArrayList<DomainLedger.Cursor> base = new ArrayList<>();
        final Map<Long, DomainLedger.Change> byId = new HashMap<>();
        final TreeMap<Long, DomainLedger.Change> byRevision = new TreeMap<>();
        long cursor;
        boolean built;

        Snapshot(long ceiling, long revision, int count) {
            this.ceiling = ceiling;
            startRevision = revision;
            initialCount = count;
        }

        boolean record(DomainLedger.Change value) {
            var old = byId.get(value.sequence());
            if (old == null && byId.size() >= limit) return false;
            if (old != null) byRevision.remove(old.revision());
            byId.put(value.sequence(), value);
            byRevision.put(value.revision(), value);
            return true;
        }
    }

    /** One authorized player's send cursor; polling consumes send state, not inventory, and never exceeds allowance. */
    public final class Receiver implements AutoCloseable {
        private final UUID player, session;
        private final long generation;
        private @Nullable Snapshot capture;
        private final LinkedHashMap<Long, DomainLedger.Change> pending = new LinkedHashMap<>();
        private long nextFrame, catchupRevision;
        private int baseIndex, offset;
        private @Nullable byte[] encoded;
        private @Nullable DomainLedger.Change inFlight;
        private boolean baseRecord, started, full = true, closed;
        private @Nullable DomainInventoryFrame.Reason failure;

        private Receiver(UUID player, UUID session, long generation, Snapshot capture) {
            this.player = player;
            this.session = session;
            this.generation = generation;
            this.capture = capture;
        }

        public boolean fullSync() {
            check();
            return full && failure == null && !closed;
        }

        /** Owner-thread readiness; failure and ledger invalidation revoke access before the failure frame is sent. */
        public boolean ready() {
            check();
            return started && !full && failure == null && !closed && ledger.isAvailable();
        }

        public boolean finished() {
            check();
            return closed;
        }

        public int pendingCount() {
            check();
            return pending.size()
                    + (inFlight != null && !baseRecord && !pending.containsKey(inFlight.sequence()) ? 1 : 0);
        }

        private void record(DomainLedger.Change value) {
            int unique = pendingCount();
            if (!pending.containsKey(value.sequence())
                    && (inFlight == null || inFlight.sequence() != value.sequence())
                    && unique >= limit) {
                fail(DomainInventoryFrame.Reason.CHANGING_TOO_FAST);
                return;
            }
            pending.put(value.sequence(), value);
        }

        void abort(DomainInventoryFrame.Reason reason) {
            check();
            fail(reason);
        }

        private void fail(DomainInventoryFrame.Reason reason) {
            if (closed || failure != null) return;
            failure = reason;
            full = false;
            clear();
        }

        private void clear() {
            pending.clear();
            capture = null;
            encoded = null;
            inFlight = null;
            offset = 0;
        }

        /** Returns at most one frame including its payload-ID envelope, or null when budget/work is insufficient. */
        public @Nullable DomainInventoryFrame poll(long allowance) {
            check();
            if (closed) return null;
            if (failure != null) {
                if (allowance < DomainInventoryFrame.HEADER_BYTES + 1) return null;
                closed = true;
                return new DomainInventoryFrame.Failed(session, generation, frame(), failure);
            }
            if (!ledger.isAvailable() || journal.failed()) {
                fail(
                        ledger.isAvailable()
                                ? DomainInventoryFrame.Reason.CHANGING_TOO_FAST
                                : DomainInventoryFrame.Reason.UNAVAILABLE);
                return poll(allowance);
            }
            if (!started) {
                if (allowance < DomainInventoryFrame.HEADER_BYTES + 12) return null;
                started = true;
                var state = Objects.requireNonNull(capture);
                return new DomainInventoryFrame.Begin(session, generation, frame(), state.ceiling, state.initialCount);
            }
            if (encoded == null) {
                if (full) {
                    var state = Objects.requireNonNull(capture);
                    if (baseIndex < state.base.size()) {
                        if (allowance <= DomainInventoryFrame.DATA_HEADER_BYTES) return null;
                        var cursor = state.base.get(baseIndex);
                        beginRecord(new DomainLedger.Change(cursor.sequence(), cursor.key(), cursor.amount(), 0), true);
                    } else {
                        if (!state.built || journal.pendingCount() != 0) return null;
                        var next = state.byRevision.higherEntry(catchupRevision);
                        if (next != null) {
                            if (allowance <= DomainInventoryFrame.DATA_HEADER_BYTES) return null;
                            beginRecord(next.getValue(), false);
                        } else {
                            if (allowance < DomainInventoryFrame.HEADER_BYTES + 12) return null;
                            var end = new DomainInventoryFrame.End(
                                    session, generation, frame(), ledger.variantCount(), ledger.revision());
                            full = false;
                            capture = null;
                            retireSnapshotIfUnused();
                            return end;
                        }
                    }
                } else {
                    if (pending.isEmpty() || allowance <= DomainInventoryFrame.DATA_HEADER_BYTES) return null;
                    beginRecord(pending.pollFirstEntry().getValue(), false);
                }
            }
            int length = (int) Math.min(
                    Math.min(
                            allowance - DomainInventoryFrame.DATA_HEADER_BYTES,
                            DomainInventoryFrame.MAXIMUM_FRAGMENT_BYTES),
                    encoded.length - offset);
            if (length <= 0) return null;
            var data = new DomainInventoryFrame.Data(
                    session,
                    generation,
                    frame(),
                    baseRecord,
                    encoded.length,
                    offset,
                    Arrays.copyOfRange(encoded, offset, offset + length));
            offset += length;
            if (offset == encoded.length) {
                if (baseRecord) baseIndex++;
                else if (full)
                    catchupRevision = Objects.requireNonNull(inFlight).revision();
                encoded = null;
                inFlight = null;
                offset = 0;
            }
            return data;
        }

        private void beginRecord(DomainLedger.Change value, boolean base) {
            inFlight = value;
            baseRecord = base;
            offset = 0;
            encoded = DomainInventoryRecordCodec.encode(value);
        }

        private long frame() {
            if (nextFrame == Long.MAX_VALUE) throw new IllegalStateException("Inventory stream sequence exhausted");
            return nextFrame++;
        }

        @Override
        public void close() {
            checkThread();
            if (DomainInventoryPublisher.this.closed) return;
            closed = true;
            clear();
            receivers.remove(player, this);
            retireSnapshotIfUnused();
        }
    }
}
