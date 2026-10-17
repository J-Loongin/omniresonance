// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Client-thread index owned by one view. Rows are keyed by live result IDs; positions also retain Shift's zero
 * placeholders until release. Query/sort/matcher changes replace the index with budgeted baseline work. The
 * 4096-ID change inbox coalesces notifications and falls back to a baseline on overflow. Close drops all state.
 */
final class DomainInventorySearch implements AutoCloseable {
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
    private @Nullable ArrayList<Long> pending;
    private ArrayList<Long> visible = new ArrayList<>();
    private List<Long> visibleReadOnly = Collections.unmodifiableList(visible);
    private Map<Long, Row> rows = Map.of(), pendingRows = Map.of();
    private Map<ResourceVariantKey, Integer> positions = Map.of(), pendingPositions = Map.of();
    private boolean wasFrozen, frozenChanges;
    private long notifiedVersion = -1;
    private Map<Long, ResourceVariantKey> visibleKeys = Map.of();
    private Map<Long, ResourceVariantKey> pendingKeys = Map.of();
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
        return visibleKeys.get(id);
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
        pendingKeys = Map.of();
        pendingRows = Map.of();
        pendingPositions = Map.of();
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
                var key = visibleKeys.get(id);
                job.positions.put(key, job.pinned.size());
                job.pinned.add(id);
                job.pinnedKeys.put(id, key);
                continue;
            }
            if (job.output == null) {
                var entry = source.after(job.cursor, job.ceiling);
                if (entry == null) {
                    job.output = job.sorted.iterator();
                    continue;
                }
                job.cursor = entry.sequence();
                var text = describe.apply(entry);
                if (query.matches(text)) {
                    var row = new Row(entry, text);
                    job.sorted.add(row);
                    job.rows.put(entry.sequence(), row);
                    job.keys.put(entry.sequence(), entry.key());
                    if (job.frozen) {
                        Integer index = job.positions.get(entry.key());
                        if (index == null) {
                            job.positions.put(entry.key(), job.pinned.size());
                            job.pinned.add(entry.sequence());
                            job.pinnedChanged = true;
                        } else {
                            long previous = job.pinned.set(index, entry.sequence());
                            job.pinnedKeys.remove(previous);
                            job.pinnedChanged |= previous != entry.sequence();
                        }
                        job.pinnedKeys.put(entry.sequence(), entry.key());
                    }
                }
            } else if (job.output.hasNext()) {
                long id = job.output.next().entry().sequence();
                int index = job.result.size();
                job.changed |= index >= job.comparedAgainst.size() || job.comparedAgainst.get(index) != id;
                job.result.add(id);
                job.resultPositions.put(job.keys.get(id), index);
            } else {
                pending = job.result;
                pendingRows = job.rows;
                pendingPositions = job.resultPositions;
                pendingKeys = job.keys;
                pendingChanged = queryWorkPending
                        || job.changed
                        || job.result.size() != job.comparedAgainst.size()
                        || visible != job.comparedAgainst
                        || job.pinnedChanged;
                if (frozen && job.pinnedChanged) {
                    visible = job.pinned;
                    visibleReadOnly = Collections.unmodifiableList(visible);
                    visibleKeys = job.pinnedKeys;
                    displayVersion = Math.incrementExact(displayVersion);
                }
                if (frozen) {
                    rows = job.rows;
                    positions = job.positions;
                    visibleKeys = job.pinnedKeys;
                    frozenChanges = true;
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
        visibleKeys = pendingKeys;
        rows = pendingRows;
        positions = pendingPositions;
        pending = null;
        pendingKeys = Map.of();
        pendingRows = Map.of();
        pendingPositions = Map.of();
    }

    /** Quantity-only updates in stable order use one O(log N) source lookup; structural edits shift a list suffix. */
    private void applyChange(long id, ResourceVariantKey key, boolean frozen) {
        Row previous = rows.remove(id);
        var entry = source.after(id - 1, id);
        Row next = null;
        if (entry != null && entry.sequence() == id) {
            var text = describe.apply(entry);
            if (query.matches(text)) next = new Row(entry, text);
        }
        Integer at = positions.get(key);
        if (frozen) {
            frozenChanges = true;
            pending = null;
            pendingKeys = Map.of();
            pendingRows = Map.of();
            pendingPositions = Map.of();
            if (next == null) return;
            rows.put(id, next);
            if (at == null) {
                positions.put(key, visible.size());
                visible.add(id);
                visibleKeys.put(id, key);
                displayVersion = Math.incrementExact(displayVersion);
            } else if (visible.get(at) != id) {
                long old = visible.set(at, id);
                rows.remove(old);
                visibleKeys.remove(old);
                visibleKeys.put(id, key);
                displayVersion = Math.incrementExact(displayVersion);
            }
            return;
        }
        boolean owned = at != null && visible.get(at) == id;
        if (owned && previous != null && next != null && order.compare(previous, next) == 0) {
            rows.put(id, next);
            return;
        }
        int oldPosition = owned ? at : -1;
        if (owned) removeAt(at);
        if (next == null) {
            if (owned) displayVersion = Math.incrementExact(displayVersion);
            return;
        }
        // A remove/reinsert may already have been observed by a baseline build.
        Integer reused = positions.get(key);
        if (reused != null) {
            long old = visible.get(reused);
            rows.remove(old);
            removeAt(reused);
        }
        int low = 0, high = visible.size();
        var compare = order;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (compare.compare(rows.get(visible.get(middle)), next) < 0) low = middle + 1;
            else high = middle;
        }
        rows.put(id, next);
        visibleKeys.put(id, key);
        visible.add(low, id);
        reindex(low);
        if (oldPosition != low || reused != null) displayVersion = Math.incrementExact(displayVersion);
    }

    private void removeAt(int index) {
        long old = visible.remove(index);
        positions.remove(visibleKeys.remove(old));
        reindex(index);
    }

    private void reindex(int first) {
        for (int i = first; i < visible.size(); i++) positions.put(visibleKeys.get(visible.get(i)), i);
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
        final long ceiling, version, matcherRevision = ClientTextSearch.matcherRevision();
        final TreeSet<Row> sorted = new TreeSet<>(order);
        final ArrayList<Long> result = new ArrayList<>();
        final List<Long> comparedAgainst = visible;
        final boolean frozen;
        final Map<Long, ResourceVariantKey> keys = new HashMap<>();
        final Map<Long, Row> rows = new HashMap<>();
        final Map<ResourceVariantKey, Integer> resultPositions = new HashMap<>();
        final Map<ResourceVariantKey, Integer> positions = new HashMap<>();
        final ArrayList<Long> pinned = new ArrayList<>();
        final Map<Long, ResourceVariantKey> pinnedKeys = new HashMap<>();
        boolean changed, pinnedChanged;
        int seed;
        long cursor;

        @Nullable
        Iterator<Row> output;

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
        visible = new ArrayList<>();
        visibleReadOnly = Collections.unmodifiableList(visible);
        rows = pendingRows = Map.of();
        positions = pendingPositions = Map.of();
        changes.clear();
        rescan = wasFrozen = frozenChanges = false;
        notifiedVersion = -1;
        visibleKeys = Map.of();
        pendingKeys = Map.of();
        pendingRows = Map.of();
        pendingPositions = Map.of();
        completedVersion = -1;
    }
}
