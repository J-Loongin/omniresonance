// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Client-thread index owned by one view. Rows are keyed by live result IDs; positions also retain Shift's zero
 * placeholders until release. Query/sort/matcher changes replace the index with budgeted baseline work. The
 * 4096-ID change inbox coalesces notifications and falls back to a baseline on overflow. Close drops all state.
 */
final class DomainInventorySearch implements AutoCloseable {
    /** Complete client mirror: identities keep monotonic IDs; reinsertion receives an ID above the prior ceiling. */
    interface Source {
        @Nullable
        DomainLedger.Cursor after(long cursor, long ceiling);

        long maximumId();

        long version();
    }

    enum Sort {
        NAME_ASC,
        NAME_DESC,
        AMOUNT_DESC,
        AMOUNT_ASC,
        MOD_ASC,
        MOD_DESC
    }

    private record Row(DomainLedger.Cursor entry, DomainInventoryQuery.Document text) {}

    private final Source source;
    private final Comparator<Row> order = this::compareRows;
    private final Function<DomainLedger.Cursor, DomainInventoryQuery.Document> describe;
    private DomainInventoryQuery query = DomainInventoryQuery.parse("");
    private Sort sort = Sort.NAME_ASC;
    private boolean invalid;
    private boolean queryWorkPending = true;
    private @Nullable Job job;
    private @Nullable DomainInventoryOrder pending;
    private DomainInventoryOrder visible = new DomainInventoryOrder();
    private List<Long> visibleReadOnly = Collections.unmodifiableList(visible);
    private Map<Long, Row> rows = Map.of(), pendingRows = Map.of();
    private boolean wasFrozen, frozenChanges, frozenOrder;
    private long notifiedVersion = -1;
    private long completedVersion = -1;
    private long displayVersion;
    private boolean pendingChanged;
    private final Map<Long, ResourceVariantKey> changes = new LinkedHashMap<>();
    private boolean rescan;

    /** Coalesces authoritative changes; bounded overflow requests a later baseline without restarting active work. */
    void changed(DomainLedger.Change change) {
        if (changes.size() >= 4096 && !changes.containsKey(change.sequence())) {
            changes.clear();
            rescan = true;
        }
        changes.put(change.sequence(), change.key());
        notifiedVersion = source.version();
    }

    private long matcherRevision = ClientTextSearch.matcherRevision();

    DomainInventorySearch(Source source, Function<DomainLedger.Cursor, DomainInventoryQuery.Document> describe) {
        this.source = source;
        this.describe = describe;
    }

    boolean query(String text) {
        try {
            var next = DomainInventoryQuery.parse(text);
            query = next;
            invalid = false;
            invalidate();
            return true;
        } catch (IllegalArgumentException malformed) {
            invalid = true;
            return false;
        }
    }

    void sort(Sort value) {
        sort = value;
        invalidate();
    }

    Sort sort() {
        return sort;
    }

    boolean invalid() {
        return invalid;
    }

    boolean working() {
        return job != null;
    }

    /** Only explicit query/index work needs a visible status; passive inventory refresh keeps usable results. */
    boolean searching() {
        return queryWorkPending && working();
    }

    /** The current query has a published result even while passive inventory changes are being indexed. */
    boolean hasCompletedQuery() {
        return !invalid && !queryWorkPending && matcherRevision == ClientTextSearch.matcherRevision();
    }

    List<Long> ids() {
        return visibleReadOnly;
    }

    @Nullable
    ResourceVariantKey key(long id) {
        return visible.key(id);
    }

    long displayVersion() {
        return displayVersion;
    }

    void refresh() {
        invalidate();
    }

    private void invalidate() {
        queryWorkPending = true;
        job = null;
        pending = null;
        pendingRows = Map.of();
        completedVersion = -1;
    }

    void tick(int maximumWork, BooleanSupplier workAvailable, boolean frozen) {
        if (wasFrozen && !frozen && frozenChanges) {
            if (pending == null || !changes.isEmpty() || rescan) {
                job = null;
                pending = null;
                completedVersion = -1;
                rescan = true;
            }
            frozenChanges = false;
        }
        wasFrozen = frozen;
        if (matcherRevision != ClientTextSearch.matcherRevision()) {
            matcherRevision = ClientTextSearch.matcherRevision();
            invalidate();
        }
        if (pending != null && !frozen) publish();
        if (job != null && job.frozen != frozen) job = null;
        if (job == null && !queryWorkPending && !rescan && !changes.isEmpty()) {
            var iterator = changes.entrySet().iterator();
            for (int work = 0; work < maximumWork && iterator.hasNext() && workAvailable.getAsBoolean(); work++) {
                var change = iterator.next();
                applyChange(change.getKey(), change.getValue(), frozen);
                iterator.remove();
            }
            if (changes.isEmpty()) completedVersion = notifiedVersion;
            return;
        }
        if (job == null && (rescan || source.version() != completedVersion)) {
            job = new Job(source.maximumId(), source.version(), frozen);
            rescan = false;
        }
        for (int work = 0; work < maximumWork && job != null && workAvailable.getAsBoolean(); work++) {
            if (job.frozen && job.seed < job.comparedAgainst.size()) {
                long id = job.comparedAgainst.get(job.seed++);
                job.pinned.insert(job.pinned.size(), id, visible.key(id));
                continue;
            }
            var entry = source.after(job.cursor, job.ceiling);
            if (entry != null) {
                job.cursor = entry.sequence();
                var text = describe.apply(entry);
                if (query.matches(text)) {
                    var row = new Row(entry, text);
                    var previous = rows.get(entry.sequence());
                    job.changed |= previous == null || order.compare(previous, row) != 0;
                    job.rows.put(entry.sequence(), row);
                    int at = job.result.insertionPoint(entry.sequence(), job.compare);
                    job.result.insert(at, entry.sequence(), entry.key());
                    if (job.frozen) {
                        int index = job.pinned.position(entry.key());
                        if (index < 0) {
                            job.pinned.insert(job.pinned.size(), entry.sequence(), entry.key());
                            job.pinnedChanged = true;
                        } else {
                            job.pinnedChanged |= job.pinned.get(index) != entry.sequence();
                            job.pinned.replaceId(entry.key(), entry.sequence());
                        }
                    }
                }
            } else {
                pending = job.result;
                pendingRows = job.rows;
                pendingChanged = frozenOrder
                        || queryWorkPending
                        || job.changed
                        || job.result.size() != job.comparedAgainst.size()
                        || visible != job.comparedAgainst
                        || job.pinnedChanged;
                if (frozen && job.pinnedChanged) {
                    visible = job.pinned;
                    visibleReadOnly = Collections.unmodifiableList(visible);
                    displayVersion = Math.incrementExact(displayVersion);
                }
                if (frozen) {
                    rows = job.rows;
                    frozenChanges = frozenOrder = true;
                }
                completedVersion = job.version;
                queryWorkPending = false;
                job = null;
                if (!frozen) publish();
            }
        }
    }

    private void publish() {
        if (pendingChanged) {
            visible = pending;
            visibleReadOnly = Collections.unmodifiableList(visible);
            displayVersion = Math.incrementExact(displayVersion);
        }
        frozenOrder = false;
        rows = pendingRows;
        pending = null;
        pendingRows = Map.of();
    }

    private final java.util.function.LongBinaryOperator compareIds =
            (left, right) -> order.compare(rows.get(left), rows.get(right));

    /** One source lookup and O(log N) index updates, including moves across the entire quantity ordering. */
    private void applyChange(long id, ResourceVariantKey key, boolean frozen) {
        Row previous = rows.remove(id);
        var entry = source.after(id - 1, id);
        Row next = null;
        if (entry != null && entry.sequence() == id) {
            var text = describe.apply(entry);
            if (query.matches(text)) next = new Row(entry, text);
        }
        int at = visible.position(key);
        if (frozen) {
            frozenChanges = frozenOrder = true;
            pending = null;
            pendingRows = Map.of();
            if (next == null) return;
            rows.put(id, next);
            if (at < 0) {
                visible.insert(visible.size(), id, key);
                displayVersion = Math.incrementExact(displayVersion);
            } else if (visible.get(at) != id) {
                rows.remove(visible.get(at));
                visible.replaceId(key, id);
                displayVersion = Math.incrementExact(displayVersion);
            }
            return;
        }
        boolean owned = at >= 0 && visible.get(at) == id;
        if (owned && previous != null && next != null && order.compare(previous, next) == 0) {
            rows.put(id, next);
            return;
        }
        int oldPosition = owned ? at : -1;
        if (owned) visible.remove(at);
        if (next == null) {
            if (owned) displayVersion = Math.incrementExact(displayVersion);
            return;
        }
        int reused = visible.position(key);
        if (reused >= 0) rows.remove(visible.remove(reused));
        rows.put(id, next);
        int index = visible.insertionPoint(id, compareIds);
        visible.insert(index, id, key);
        if (oldPosition != index || reused >= 0) displayVersion = Math.incrementExact(displayVersion);
    }

    private int compareRows(Row left, Row right) {
        int compared;
        switch (sort) {
            case AMOUNT_ASC, AMOUNT_DESC -> {
                compared = Integer.compare(
                        rank(left.text().type()), rank(right.text().type()));
                if (compared == 0)
                    compared = left.text().type().compareTo(right.text().type());
                if (compared == 0)
                    compared = sort == Sort.AMOUNT_ASC
                            ? Long.compare(left.entry().amount(), right.entry().amount())
                            : Long.compare(right.entry().amount(), left.entry().amount());
            }
            case MOD_ASC, MOD_DESC -> {
                compared = sort == Sort.MOD_ASC
                        ? left.text().mod().compareTo(right.text().mod())
                        : right.text().mod().compareTo(left.text().mod());
                if (compared == 0)
                    compared = left.text().name().compareTo(right.text().name());
            }
            default ->
                compared = sort == Sort.NAME_ASC
                        ? left.text().name().compareTo(right.text().name())
                        : right.text().name().compareTo(left.text().name());
        }
        return compared != 0
                ? compared
                : left.entry().key().compareIdentity(right.entry().key());
    }

    private static int rank(String type) {
        return switch (type) {
            case "minecraft:item" -> 0;
            case "minecraft:fluid" -> 1;
            case "neoforge:energy" -> 2;
            default -> 3;
        };
    }

    private final class Job {
        final long ceiling, version;
        final DomainInventoryOrder result = new DomainInventoryOrder();
        final List<Long> comparedAgainst = visible;
        final boolean frozen;
        final Map<Long, Row> rows = new HashMap<>();
        final java.util.function.LongBinaryOperator compare =
                (left, right) -> order.compare(rows.get(left), rows.get(right));
        final DomainInventoryOrder pinned = new DomainInventoryOrder();
        boolean changed, pinnedChanged;
        int seed;
        long cursor;

        Job(long ceiling, long version, boolean frozen) {
            this.frozen = frozen;
            this.ceiling = ceiling;
            this.version = version;
        }
    }

    @Override
    public void close() {
        queryWorkPending = true;
        job = null;
        pending = null;
        visible = new DomainInventoryOrder();
        visibleReadOnly = Collections.unmodifiableList(visible);
        rows = pendingRows = Map.of();
        changes.clear();
        rescan = wasFrozen = frozenChanges = frozenOrder = false;
        notifiedVersion = -1;
        pendingRows = Map.of();
        completedVersion = -1;
    }
}
