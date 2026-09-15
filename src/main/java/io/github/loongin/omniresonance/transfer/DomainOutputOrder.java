// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One server-thread output configuration publication. Sorts once at construction, then visits candidates in
 * strict priority order without retaining negative capacity observations. This class owns no resource quantities,
 * native handles or ledger-wide cache. Callers own one cursor per live complete variant and discard it when that
 * variant or this publication retires; cursor storage is bounded by live variants and published priority groups.
 */
public final class DomainOutputOrder {
    public enum State {
        NO_CAPACITY,
        WAITING_BUDGET,
        COMMITTED,
        FAILED
    }

    /** Known execution evidence. Failures may report proven movement; an unknown outcome never invents movement. */
    public record Attempt(State state, long moved) {
        public Attempt {
            Objects.requireNonNull(state);
            if (moved < 0
                    || state == State.COMMITTED && moved == 0
                    || (state == State.NO_CAPACITY || state == State.WAITING_BUDGET) && moved != 0)
                throw new IllegalArgumentException("Invalid output attempt evidence");
        }
    }

    /** Detached disposition; null output means none was attempted or the budget ended between candidates. */
    public record Selection(@Nullable UUID outputId, Attempt attempt) {}

    /** Trusted server-thread candidate. Metadata is immutable for this publication. An attempt first checks
     * current enablement, redstone, filter, due time and quota, then performs bounded native preparation/commit
     * under the supplied shared budget. NO_CAPACITY must prove this entire configuration cannot receive this
     * variant now, not merely that one slot rejected. Incomplete preparation returns WAITING_BUDGET. One commit
     * may overrun a soft budget only by its established bounded unit; failure stops this allocation immediately.
     * All extracted resources must be settled before returning. No method may recursively reuse its cursor. */
    public interface Output {
        UUID id();

        int priority();

        Attempt attempt(ResourceVariant variant, TransferWorkBudget budget);
    }

    private record Entry(UUID id, int priority, Output output) {}

    private record Group(int start, int size) {}

    private final Thread owner = Thread.currentThread();
    private final List<Entry> entries;
    private final List<Group> groups;
    private long nextSeed;

    /** Copies bounded metadata, rejects duplicate identities, and sorts without world access or resource mutation. */
    public DomainOutputOrder(List<? extends Output> outputs) {
        Objects.requireNonNull(outputs);
        if (outputs.size() > 262144) throw new IllegalArgumentException("Too many domain output configurations");
        List<Entry> entries = new ArrayList<>(outputs.size());
        HashSet<UUID> ids = new HashSet<>();
        for (Output output : outputs) {
            Objects.requireNonNull(output);
            UUID id = Objects.requireNonNull(output.id());
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate domain output");
            entries.add(new Entry(id, output.priority(), output));
        }
        entries.sort(Comparator.comparingInt(Entry::priority).reversed().thenComparing(Entry::id));
        this.entries = List.copyOf(entries);
        List<Group> groups = new ArrayList<>();
        for (int start = 0; start < entries.size(); ) {
            int end = start + 1;
            while (end < entries.size()
                    && entries.get(end).priority() == entries.get(start).priority()) end++;
            groups.add(new Group(start, end - start));
            start = end;
        }
        this.groups = List.copyOf(groups);
    }

    /** Creates caller-owned execution rotation, not a simulation. This publication does not retain the cursor. */
    public Cursor cursor(ResourceVariantKey key) {
        checkThread();
        return new Cursor(this, Objects.requireNonNull(key), nextSeed);
    }

    /**
     * Performs at most one successful/failed commit, checking all higher contenders in this same invocation.
     * Starts from the highest priority after every pause, so prior-tick capacity failures never authorize a lower
     * output. Only proven movement advances a tie cursor; simulations and refusal do not. Existing cursors cannot
     * be reused with another full variant or a replacement publication. Native exceptions propagate immediately,
     * without trying any lower candidate or inferring quantities; caller applies its normal failure backoff.
     */
    public Selection step(ResourceVariant variant, Cursor cursor, TransferWorkBudget budget) {
        checkThread();
        Objects.requireNonNull(variant);
        Objects.requireNonNull(cursor);
        Objects.requireNonNull(budget);
        if (cursor.publication != this || !cursor.key.equals(variant.key()))
            throw new IllegalArgumentException("Stale or mismatched output cursor");
        if (cursor.busy) throw new IllegalStateException("Recursive domain output allocation");
        cursor.busy = true;
        try {
            for (int index = 0; index < groups.size(); index++) {
                Group group = groups.get(index);
                int start = cursor.offsets.getOrDefault(index, (int) (cursor.seed % group.size()));
                for (int offset = 0; offset < group.size(); offset++) {
                    if (!budget.canStart()) return new Selection(null, new Attempt(State.WAITING_BUDGET, 0));
                    int relative = (start + offset) % group.size();
                    Entry entry = entries.get(group.start() + relative);
                    Attempt attempt = Objects.requireNonNull(entry.output().attempt(variant, budget));
                    if (attempt.moved() > 0 && group.size() > 1)
                        cursor.offsets.put(index, (relative + 1) % group.size());
                    // New live variants inherit a rotating start, without retaining retired-key history.
                    if (attempt.moved() > 0) nextSeed = nextSeed == Long.MAX_VALUE ? 0 : nextSeed + 1;
                    if (attempt.state() != State.NO_CAPACITY) return new Selection(entry.id(), attempt);
                }
            }
            return new Selection(null, new Attempt(State.NO_CAPACITY, 0));
        } finally {
            cursor.busy = false;
        }
    }

    private void checkThread() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Domain output order accessed off owner thread");
    }

    /** Sparse offsets only for tie groups actually used; singleton priorities allocate no entries.
     * Bounded by this publication's groups; no old acceptance promise or quantity is retained. */
    public static final class Cursor {
        private final DomainOutputOrder publication;
        private final ResourceVariantKey key;
        private final Map<Integer, Integer> offsets = new HashMap<>();
        private boolean busy;
        private final long seed;

        private Cursor(DomainOutputOrder publication, ResourceVariantKey key, long seed) {
            this.publication = publication;
            this.key = key;
            this.seed = seed;
        }
    }
}
