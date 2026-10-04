// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/** Client-thread incremental query/index construction. One frozen result and one replacement job, no full-map copies. */
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
    private final Function<DomainLedger.Cursor, DomainInventoryQuery.Document> describe;
    private DomainInventoryQuery query = DomainInventoryQuery.parse("");
    private Sort sort = Sort.NAME_ASC;
    private boolean invalid;
    private @Nullable Job job;
    private @Nullable List<Long> pending;
    private List<Long> visible = List.of();
    private Map<Long, ResourceVariantKey> visibleKeys = Map.of();
    private Map<Long, ResourceVariantKey> pendingKeys = Map.of();
    private long completedVersion = -1;
    private long displayVersion;
    private boolean pendingChanged;
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

    boolean settled() {
        return !invalid
                && job == null
                && pending == null
                && completedVersion == source.version()
                && matcherRevision == ClientTextSearch.matcherRevision();
    }

    List<Long> ids() {
        return visible;
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
        job = null;
        pending = null;
        pendingKeys = Map.of();
        completedVersion = -1;
    }

    void tick(int maximumWork, BooleanSupplier workAvailable, boolean frozen) {
        if (matcherRevision != ClientTextSearch.matcherRevision()) {
            matcherRevision = ClientTextSearch.matcherRevision();
            invalidate();
        }
        if (pending != null && !frozen) publish();
        if (job != null && job.frozen != frozen) job = null;
        if (job == null && source.version() != completedVersion)
            job = new Job(source.maximumId(), source.version(), frozen);
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
                    job.sorted.add(new Row(entry, text));
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
            } else {
                pending = Collections.unmodifiableList(job.result);
                pendingKeys = job.keys;
                pendingChanged = job.changed
                        || job.result.size() != job.comparedAgainst.size()
                        || visible != job.comparedAgainst
                        || job.pinnedChanged;
                if (frozen && job.pinnedChanged) {
                    visible = Collections.unmodifiableList(job.pinned);
                    visibleKeys = job.pinnedKeys;
                    displayVersion = Math.incrementExact(displayVersion);
                }
                completedVersion = job.version;
                job = null;
                if (!frozen) publish();
            }
        }
    }

    private void publish() {
        if (pendingChanged) {
            visible = pending;
            visibleKeys = pendingKeys;
            displayVersion = Math.incrementExact(displayVersion);
        }
        pending = null;
        pendingKeys = Map.of();
    }

    private Comparator<Row> comparator() {
        return (left, right) -> {
            int compared;
            switch (sort) {
                case AMOUNT_ASC, AMOUNT_DESC -> {
                    compared = Integer.compare(
                            rank(left.text().type()), rank(right.text().type()));
                    if (compared == 0)
                        compared = left.text().type().compareTo(right.text().type());
                    if (compared == 0)
                        compared = sort == Sort.AMOUNT_ASC
                                ? Long.compare(
                                        left.entry().amount(), right.entry().amount())
                                : Long.compare(
                                        right.entry().amount(), left.entry().amount());
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
        };
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
        final TreeSet<Row> sorted = new TreeSet<>(comparator());
        final ArrayList<Long> result = new ArrayList<>();
        final List<Long> comparedAgainst = visible;
        final boolean frozen;
        final Map<Long, ResourceVariantKey> keys = new HashMap<>();
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
        job = null;
        pending = null;
        visible = List.of();
        visibleKeys = Map.of();
        pendingKeys = Map.of();
        completedVersion = -1;
    }
}
