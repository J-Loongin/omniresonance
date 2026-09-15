// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One client-thread terminal view. Temporary inventory stays hidden until validated End; all quantities are
 * authoritative absolute values. Failed streams release inventory and require explicit begin, never auto-retry.
 * Keys and cursors are immutable, maps have one owner; no world objects, adapters, or past-view caches are retained.
 */
final class DomainInventoryMirror implements AutoCloseable {
    private enum State {
        EMPTY,
        RECEIVING,
        READY,
        FAILED
    }

    private final Thread owner = Thread.currentThread();
    private State state = State.EMPTY;
    private @Nullable UUID session;
    private long generation;
    private long ceiling;
    private long nextBlock;
    private long lastBaseId;
    private long revision;
    private boolean catchup;
    private java.util.NavigableMap<Long, DomainLedger.Cursor> inventory = new java.util.TreeMap<>();
    private Map<ResourceVariantKey, Long> identities = new HashMap<>();
    private Map<Long, DomainLedger.Cursor> visible = Map.of();

    /** Starts an explicitly authorized view, clearing its predecessor rather than exposing a partial replacement. */
    void begin(UUID session, long generation, long ceiling) {
        checkThread();
        Objects.requireNonNull(session);
        if (generation <= 0 || ceiling < 0) throw new IllegalArgumentException("Invalid inventory stream identity");
        close();
        this.session = session;
        this.generation = generation;
        this.ceiling = ceiling;
        inventory = new java.util.TreeMap<>();
        identities = new HashMap<>();
        nextBlock = lastBaseId = revision = 0;
        catchup = false;
        state = State.RECEIVING;
    }

    boolean base(UUID session, long generation, long block, DomainLedger.Cursor entry) {
        checkThread();
        if (!accept(session, generation, block)) return false;
        if (state != State.RECEIVING
                || catchup
                || entry == null
                || entry.key() == null
                || entry.sequence() <= lastBaseId
                || entry.sequence() > ceiling
                || entry.amount() <= 0) return fail();
        if (inventory.containsKey(entry.sequence()) || identities.containsKey(entry.key())) return fail();
        inventory.put(entry.sequence(), entry);
        identities.put(entry.key(), entry.sequence());
        lastBaseId = entry.sequence();
        return true;
    }

    boolean change(UUID session, long generation, long block, DomainLedger.Change change) {
        checkThread();
        if (!accept(session, generation, block)) return false;
        if (change == null
                || change.key() == null
                || change.sequence() <= 0
                || change.amount() < 0
                || change.revision() < 0) return fail();
        catchup = true;
        DomainLedger.Cursor existing = inventory.get(change.sequence());
        if (existing != null && !existing.key().equals(change.key())) return fail();
        if (change.amount() == 0) {
            if (existing != null) {
                inventory.remove(change.sequence());
                identities.remove(existing.key());
            }
        } else {
            Long oldId = identities.get(change.key());
            if (oldId != null && oldId != change.sequence()) return fail();
            inventory.put(change.sequence(), new DomainLedger.Cursor(change.sequence(), change.key(), change.amount()));
            identities.put(change.key(), change.sequence());
        }
        revision = Math.max(revision, change.revision());
        return true;
    }

    boolean finish(UUID session, long generation, long block, int finalCount, long finalRevision) {
        checkThread();
        if (!accept(session, generation, block)) return false;
        if (state != State.RECEIVING || finalCount != inventory.size() || finalRevision < revision) return fail();
        revision = finalRevision;
        visible = Collections.unmodifiableMap(inventory);
        state = State.READY;
        return true;
    }

    @Nullable
    DomainLedger.Cursor after(long id, long ceiling) {
        checkThread();
        if (state != State.READY) return null;
        var next = inventory.higherEntry(id);
        return next == null || next.getKey() > ceiling ? null : next.getValue();
    }

    long maximumId() {
        checkThread();
        return inventory.isEmpty() ? 0 : inventory.lastKey();
    }

    boolean ready() {
        checkThread();
        return state == State.READY;
    }

    boolean failed() {
        checkThread();
        return state == State.FAILED;
    }

    long revision() {
        checkThread();
        return revision;
    }

    /** Read-only current inventory, empty before End and after failure/close. Retained older views are cleared. */
    Map<Long, DomainLedger.Cursor> entries() {
        checkThread();
        return visible;
    }

    private boolean accept(UUID session, long generation, long block) {
        if (!Objects.equals(this.session, session)
                || this.generation != generation
                || state == State.EMPTY
                || state == State.FAILED) return false;
        if (block != nextBlock || nextBlock == Long.MAX_VALUE) return fail();
        nextBlock++;
        return true;
    }

    boolean reject() {
        checkThread();
        return fail();
    }

    private boolean fail() {
        inventory.clear();
        identities.clear();
        visible = Map.of();
        state = State.FAILED;
        return false;
    }

    @Override
    public void close() {
        checkThread();
        inventory.clear();
        identities.clear();
        visible = Map.of();
        session = null;
        state = State.EMPTY;
    }

    private void checkThread() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Inventory mirror accessed off client thread");
    }
}
