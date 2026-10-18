// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Production index/work cap with deterministic sources; logged time is observation, never a pass threshold. */
class DomainInventorySearchScaleTest {
    private static final class Source implements DomainInventorySearch.Source {
        final TreeMap<Long, DomainLedger.Cursor> entries = new TreeMap<>();
        long version, maximum;
        int descriptions;

        DomainLedger.Change put(long id, long amount) {
            var key = key(id);
            maximum = Math.max(maximum, id);
            if (amount == 0) entries.remove(id);
            else entries.put(id, new DomainLedger.Cursor(id, key, amount));
            return new DomainLedger.Change(id, key, amount, ++version);
        }

        public DomainLedger.Cursor after(long id, long ceiling) {
            var entry = entries.higherEntry(id);
            return entry == null || entry.getKey() > ceiling ? null : entry.getValue();
        }

        public long maximumId() {
            return maximum;
        }

        public long version() {
            return version;
        }

        DomainInventorySearch search() {
            return new DomainInventorySearch(this, entry -> {
                descriptions++;
                long id = entry.sequence();
                return new DomainInventoryQuery.Document(
                        "Resource " + id,
                        "example",
                        "example:r_" + id,
                        "minecraft:item",
                        List.of("example:group_" + id % 17),
                        () -> "",
                        "",
                        () -> entries.containsKey(id) ? entries.get(id).amount() : -1);
            });
        }
    }

    private static ResourceVariantKey key(long id) {
        return new ResourceVariantKey(
                ResourceLocation.parse("minecraft:item"),
                ByteBuffer.allocate(8).putLong(id).array());
    }

    private static int settle(DomainInventorySearch search) {
        int ticks = 0;
        do {
            search.tick(DomainInventoryView.SEARCH_WORK, () -> true, false);
            assertTrue(++ticks < 100);
        } while (search.working() || !search.hasCompletedQuery());
        return ticks;
    }

    @Test
    void largeInitialResultsAndQueriesHaveOnePassWorkBounds() {
        for (int size : new int[] {1000, 10000, 50000}) {
            var source = new Source();
            for (int i = 1; i <= size; i++) source.put(i, i);
            try (var search = source.search()) {
                int ticks = settle(search);
                assertEquals((size + DomainInventoryView.SEARCH_WORK) / DomainInventoryView.SEARCH_WORK, ticks);
                assertEquals(size, search.ids().size());
                assertEquals(size, source.descriptions);
                var prior = search.ids();
                search.query("#example:group_7");
                search.tick(1, () -> true, false);
                assertEquals(prior, search.ids());
                int queryTicks = settle(search);
                int expected = 0;
                for (long id : source.entries.keySet()) if (id % 17 == 7) expected++;
                assertEquals(expected, search.ids().size());
                assertEquals(size * 2, source.descriptions);
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .info(
                                "Index scale: variants={}, workCap={}, initialTicks={}, queryTicksAfterFirstStep={}",
                                size,
                                DomainInventoryView.SEARCH_WORK,
                                ticks,
                                queryTicks);
            }
        }
    }

    @Test
    void largeAmountBurstsStayOrderedInBothDirectionsWithoutARescan() {
        for (var sort : List.of(DomainInventorySearch.Sort.AMOUNT_DESC, DomainInventorySearch.Sort.AMOUNT_ASC)) {
            var source = new Source();
            for (int i = 1; i <= 50000; i++) source.put(i, i);
            try (var search = source.search()) {
                search.sort(sort);
                settle(search);
                for (int round = 0; round < 8; round++) {
                    for (int i = 0; i < 128; i++) {
                        long id = 1 + (i * 389L + round * 17L) % 50000;
                        search.changed(source.put(id, 50001 + round * 128L + i));
                    }
                    int before = source.descriptions;
                    long started = System.nanoTime();
                    search.tick(128, () -> true, false);
                    long elapsed = System.nanoTime() - started;
                    assertEquals(128, source.descriptions - before);
                    assertEquals(50000, new HashSet<>(search.ids()).size());
                    long previous = sort == DomainInventorySearch.Sort.AMOUNT_ASC ? Long.MIN_VALUE : Long.MAX_VALUE;
                    for (long id : search.ids()) {
                        long amount = source.entries.get(id).amount();
                        assertTrue(
                                sort == DomainInventorySearch.Sort.AMOUNT_ASC
                                        ? amount >= previous
                                        : amount <= previous);
                        previous = amount;
                    }
                    org.slf4j.LoggerFactory.getLogger(getClass())
                            .info(
                                    "Index amount burst: variants=50000, changes=128, sort={}, round={}, processingNanos={}",
                                    sort,
                                    round,
                                    elapsed);
                }
            }
        }
    }

    @Test
    void longShiftChurnReleasesPlaceholdersAndPreservesIdentityPositions() {
        var source = new Source();
        var live = new ArrayList<Long>();
        for (long id = 1; id <= 256; id++) {
            source.put(id, 1);
            live.add(id);
        }
        try (var search = source.search()) {
            settle(search);
            var positions = new ArrayList<>(search.ids());
            long next = 257;
            for (int round = 0; round < 200; round++) {
                for (int i = 0; i < 64; i++) {
                    int slot = (round * 64 + i) % 256;
                    search.changed(source.put(live.get(slot), 0));
                    long added = next++;
                    source.put(added, 1);
                    search.changed(new DomainLedger.Change(added, key(added), 1, source.version()));
                    live.set(slot, added);
                    positions.add(added);
                }
                search.tick(128, () -> true, true);
                assertEquals(positions.size(), search.ids().size());
                assertEquals(positions.getFirst(), search.ids().getFirst());
                assertEquals(positions.getLast(), search.ids().getLast());
            }
            assertEquals(13056, search.ids().size());
            settle(search);
            assertEquals(256, search.ids().size());
            assertEquals(new HashSet<>(live), new HashSet<>(search.ids()));
            search.close();
            assertTrue(search.ids().isEmpty());
            assertFalse(search.working());
        }
    }
}
