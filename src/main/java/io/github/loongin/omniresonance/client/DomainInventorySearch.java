// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.storage.DomainLedger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
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

    List<Long> ids() {
        return visible;
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
        completedVersion = -1;
    }

    void tick(int maximumWork, BooleanSupplier workAvailable, boolean frozen) {
        if (matcherRevision != ClientTextSearch.matcherRevision()) {
            matcherRevision = ClientTextSearch.matcherRevision();
            invalidate();
        }
        if (pending != null && !frozen) publish();
        if (job == null && source.version() != completedVersion) job = new Job(source.maximumId(), source.version());
        for (int work = 0; work < maximumWork && job != null && workAvailable.getAsBoolean(); work++) {
            if (job.output == null) {
                var entry = source.after(job.cursor, job.ceiling);
                if (entry == null) {
                    job.output = job.sorted.iterator();
                    continue;
                }
                job.cursor = entry.sequence();
                var text = describe.apply(entry);
                if (query.matches(text)) job.sorted.add(new Row(entry, text));
            } else if (job.output.hasNext()) {
                long id = job.output.next().entry().sequence();
                int index = job.result.size();
                job.changed |= index >= job.comparedAgainst.size() || job.comparedAgainst.get(index) != id;
                job.result.add(id);
            } else {
                pending = Collections.unmodifiableList(job.result);
                pendingChanged = job.changed
                        || job.result.size() != job.comparedAgainst.size()
                        || visible != job.comparedAgainst;
                completedVersion = job.version;
                job = null;
                if (!frozen) publish();
            }
        }
    }

    private void publish() {
        if (pendingChanged) {
            visible = pending;
            displayVersion = Math.incrementExact(displayVersion);
        }
        pending = null;
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
        boolean changed;
        long cursor;

        @Nullable
        Iterator<Row> output;

        Job(long ceiling, long version) {
            this.ceiling = ceiling;
            this.version = version;
        }
    }

    @Override
    public void close() {
        job = null;
        pending = null;
        visible = List.of();
        completedVersion = -1;
    }
}
