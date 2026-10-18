// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.List;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class DomainInventorySearchTest {
    @Test
    void incrementalChangesRespectAmountQueriesSortingAndFrozenIdentityReuse() {
        var source = new DeltaSource();
        var a = new ResourceVariantKey(ResourceLocation.parse("minecraft:item"), new byte[] {1});
        var b = new ResourceVariantKey(ResourceLocation.parse("minecraft:item"), new byte[] {2});
        source.put(1, a, 10);
        source.put(2, b, 20);
        try (var search = source.search()) {
            search.sort(DomainInventorySearch.Sort.AMOUNT_DESC);
            search.tick(100, () -> true, false);
            assertEquals(List.of(2L, 1L), search.ids());
            search.changed(source.put(1, a, 30));
            search.tick(100, () -> true, false);
            assertEquals(List.of(1L, 2L), search.ids());
            search.changed(source.put(1, a, 0));
            search.tick(100, () -> true, true);
            assertEquals(List.of(1L, 2L), search.ids());
            search.changed(source.put(3, a, 5));
            search.tick(100, () -> true, true);
            assertEquals(List.of(3L, 2L), search.ids());
            search.tick(100, () -> true, false);
            assertEquals(List.of(2L, 3L), search.ids());
            search.query("$64");
            search.tick(100, () -> true, false);
            assertTrue(search.ids().isEmpty());
            search.changed(source.put(3, a, 64));
            search.tick(100, () -> true, false);
            assertEquals(List.of(3L), search.ids());
            search.changed(source.put(3, a, 63));
            search.tick(100, () -> true, false);
            assertTrue(search.ids().isEmpty());
        }
    }

    @Test
    void changesDuringBaselineAndOverflowDoNotLoseRowsOrRestartActiveWork() {
        var source = new DeltaSource();
        var key = new ResourceVariantKey(ResourceLocation.parse("minecraft:item"), new byte[] {1});
        source.put(1, key, 1);
        try (var search = source.search()) {
            search.tick(1, () -> true, false);
            search.changed(source.put(1, key, 0));
            search.changed(source.put(2, key, 2));
            for (int i = 0; i < 8; i++) search.tick(4, () -> true, false);
            assertEquals(List.of(2L), search.ids());
            for (int i = 3; i <= 5002; i++)
                search.changed(source.put(
                        i,
                        new ResourceVariantKey(
                                ResourceLocation.parse("minecraft:item"),
                                java.nio.ByteBuffer.allocate(4).putInt(i).array()),
                        1));
            for (int i = 0; i < 120; i++) search.tick(128, () -> true, false);
            assertEquals(5001, search.ids().size());
            assertEquals(5001, new java.util.HashSet<>(search.ids()).size());
        }
    }

    @Test
    void completeBaselinePublishesWithoutASecondLinearOutputPass() {
        var source = new DeltaSource();
        for (int i = 1; i <= 10_000; i++) source.put(i, scaleKey(i), i);
        try (var search = source.search()) {
            search.tick(10_001, () -> true, false);
            assertEquals(10_000, search.ids().size());
            assertFalse(search.working());
        }
    }

    @Test
    void scatteredAmountMovesDoNotAllocateWholeSuffixPositionIndexes() {
        var source = new DeltaSource();
        for (int i = 1; i <= 50_000; i++) source.put(i, scaleKey(i), i);
        try (var search = source.search()) {
            search.sort(DomainInventorySearch.Sort.AMOUNT_DESC);
            search.tick(100_010, () -> true, false);
            for (int i = 0; i < 128; i++) {
                long id = 1 + (i * 389L) % 50_000;
                search.changed(source.put(id, scaleKey((int) id), 50_001 + i));
            }
            var memory = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            assertTrue(memory.isThreadAllocatedMemorySupported());
            memory.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId();
            long before = memory.getThreadAllocatedBytes(thread);
            search.tick(128, () -> true, false);
            long allocated = memory.getThreadAllocatedBytes(thread) - before;
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .info("Index allocation: variants=50000, changes=128, allocatedBytes={}", allocated);
            assertTrue(
                    allocated < 16L * 1024 * 1024,
                    "128 changes rebuilt large suffix indexes: allocated " + allocated + " bytes");
            assertEquals(50_000, search.ids().size());
            for (int i = 0; i < 128; i++)
                assertEquals(1 + ((127 - i) * 389L) % 50_000, search.ids().get(i));
        }
    }

    @Test
    void identityReintroducedBehindTheBaselineCursorDoesNotDuplicateTheResult() {
        var source = new DeltaSource();
        var key = scaleKey(1);
        source.put(1, key, 10);
        source.put(2, scaleKey(2), 20);
        source.put(3, scaleKey(3), 30);
        try (var search = source.search()) {
            search.tick(1, () -> true, false);
            search.changed(source.put(1, key, 0));
            // New identities cannot enter the active baseline ceiling; the queued delta must restore this one.
            search.changed(source.put(4, key, 30));
            for (int i = 0; i < 4; i++) search.tick(10, () -> true, false);
            assertEquals(3, search.ids().size());
            assertEquals(java.util.Set.of(2L, 3L, 4L), new java.util.HashSet<>(search.ids()));
        }
    }

    @Test
    void allSixSortsRetainTheirExistingTypeNameModAndIdentityOrder() {
        var source = new DeltaSource();
        String[] names = {"z", "a", "c", "b", "a", "x"};
        String[] mods = {"b", "a", "b", "a", "c", "a"};
        String[] types = {
            "minecraft:item",
            "minecraft:item",
            "minecraft:fluid",
            "neoforge:energy",
            "minecraft:item",
            "minecraft:fluid"
        };
        for (int i = 0; i < 6; i++)
            source.put(
                    i + 1,
                    new ResourceVariantKey(ResourceLocation.parse(types[i]), new byte[] {(byte) (i + 1)}),
                    (i + 1) * 10);
        var expected = java.util.Map.of(
                DomainInventorySearch.Sort.NAME_ASC, List.of(2L, 5L, 4L, 3L, 6L, 1L),
                DomainInventorySearch.Sort.NAME_DESC, List.of(1L, 6L, 3L, 4L, 2L, 5L),
                DomainInventorySearch.Sort.MOD_ASC, List.of(2L, 4L, 6L, 3L, 1L, 5L),
                DomainInventorySearch.Sort.MOD_DESC, List.of(5L, 3L, 1L, 2L, 4L, 6L),
                DomainInventorySearch.Sort.AMOUNT_DESC, List.of(5L, 2L, 1L, 6L, 3L, 4L),
                DomainInventorySearch.Sort.AMOUNT_ASC, List.of(1L, 2L, 5L, 3L, 6L, 4L));
        try (var search = new DomainInventorySearch(source, entry -> {
            int index = (int) entry.sequence() - 1;
            return new DomainInventoryQuery.Document(
                    names[index], mods[index], "sample", types[index], List.of(), () -> "");
        })) {
            for (var sort : DomainInventorySearch.Sort.values()) {
                search.sort(sort);
                search.tick(7, () -> true, false);
                assertEquals(expected.get(sort), search.ids(), sort.name());
            }
        }
    }

    private static ResourceVariantKey scaleKey(int id) {
        return new ResourceVariantKey(
                ResourceLocation.parse("minecraft:item"),
                java.nio.ByteBuffer.allocate(4).putInt(id).array());
    }

    private static final class DeltaSource implements DomainInventorySearch.Source {
        final TreeMap<Long, DomainLedger.Cursor> data = new TreeMap<>();
        long version;

        DomainLedger.Change put(long id, ResourceVariantKey key, long amount) {
            if (amount == 0) data.remove(id);
            else data.put(id, new DomainLedger.Cursor(id, key, amount));
            return new DomainLedger.Change(id, key, amount, ++version);
        }

        public DomainLedger.Cursor after(long cursor, long ceiling) {
            var next = data.higherEntry(cursor);
            return next == null || next.getKey() > ceiling ? null : next.getValue();
        }

        public long maximumId() {
            return data.isEmpty() ? 0 : data.lastKey();
        }

        public long version() {
            return version;
        }

        DomainInventorySearch search() {
            return new DomainInventorySearch(
                    this,
                    entry -> new DomainInventoryQuery.Document(
                            "name",
                            "minecraft",
                            "minecraft:sample",
                            "minecraft:item",
                            List.of(),
                            () -> "",
                            "",
                            () -> data.containsKey(entry.sequence())
                                    ? data.get(entry.sequence()).amount()
                                    : -1));
        }
    }

    @Test
    void passiveDeltaOnlyDescribesChangedRowsInALargeCompletedInventory() {
        for (boolean frozen : new boolean[] {false, true}) {
            var data = new TreeMap<Long, DomainLedger.Cursor>();
            for (int i = 1; i <= 4096; i++)
                data.put(
                        (long) i,
                        new DomainLedger.Cursor(
                                i,
                                new ResourceVariantKey(
                                        ResourceLocation.parse("minecraft:item"),
                                        java.nio.ByteBuffer.allocate(4)
                                                .putInt(i)
                                                .array()),
                                64));
            long[] version = {0};
            int[] reads = {0};
            var search = new DomainInventorySearch(
                    new DomainInventorySearch.Source() {
                        public DomainLedger.Cursor after(long id, long ceiling) {
                            var next = data.higherEntry(id);
                            return next == null || next.getKey() > ceiling ? null : next.getValue();
                        }

                        public long maximumId() {
                            return data.lastKey();
                        }

                        public long version() {
                            return version[0];
                        }
                    },
                    entry -> {
                        reads[0]++;
                        return new DomainInventoryQuery.Document(
                                "item" + entry.sequence(),
                                "minecraft",
                                "minecraft:item",
                                "minecraft:item",
                                List.of(),
                                () -> "");
                    });
            search.tick(10000, () -> true, false);
            reads[0] = 0;
            var old = data.get(1L);
            data.put(1L, new DomainLedger.Cursor(1, old.key(), 63));
            version[0]++;
            search.changed(new DomainLedger.Change(1, old.key(), 63, version[0]));
            search.tick(128, () -> true, frozen);
            assertEquals(1, reads[0], "One changed quantity restarted whole-inventory indexing");
            var added = new ResourceVariantKey(ResourceLocation.parse("minecraft:item"), new byte[] {99});
            data.put(4097L, new DomainLedger.Cursor(4097, added, 1));
            version[0]++;
            search.changed(new DomainLedger.Change(4097, added, 1, version[0]));
            search.tick(128, () -> true, frozen);
            assertEquals(4097, search.ids().size(), "A new match waited behind a whole-inventory scan");
            assertEquals(2, reads[0]);
        }
    }

    @Test
    void shiftAppendsNewIdentitiesAndReusesRemovedIdentityPosition() {
        var data = new TreeMap<Long, DomainLedger.Cursor>();
        var a = new ResourceVariantKey(ResourceLocation.parse("minecraft:item"), new byte[] {1});
        var b = new ResourceVariantKey(ResourceLocation.parse("minecraft:item"), new byte[] {2});
        data.put(1L, new DomainLedger.Cursor(1, a, 64));
        long[] version = {0};
        var search = new DomainInventorySearch(
                new DomainInventorySearch.Source() {
                    public DomainLedger.Cursor after(long cursor, long ceiling) {
                        var next = data.higherEntry(cursor);
                        return next == null || next.getKey() > ceiling ? null : next.getValue();
                    }

                    public long maximumId() {
                        return data.isEmpty() ? 0 : data.lastKey();
                    }

                    public long version() {
                        return version[0];
                    }
                },
                entry -> new DomainInventoryQuery.Document(
                        entry.key().equals(a) ? "zinc" : "apple",
                        "minecraft",
                        "minecraft:test",
                        "minecraft:item",
                        List.of(),
                        () -> ""));
        search.tick(100, () -> true, false);
        data.remove(1L);
        data.put(2L, new DomainLedger.Cursor(2, b, 10));
        version[0]++;
        search.tick(100, () -> true, true);
        assertEquals(List.of(1L, 2L), search.ids());
        data.put(3L, new DomainLedger.Cursor(3, a, 32));
        version[0]++;
        search.tick(100, () -> true, true);
        assertEquals(List.of(3L, 2L), search.ids());
        data.remove(3L);
        version[0]++;
        search.tick(100, () -> true, true);
        assertEquals(List.of(3L, 2L), search.ids());
        search.tick(100, () -> true, false);
        assertEquals(List.of(2L), search.ids());
    }

    @Test
    void boundedQueriesKeepPriorResultsOnSyntaxErrorAndShiftFreezesOnlyPublication() {
        var data = new TreeMap<Long, DomainLedger.Cursor>();
        for (int i = 1; i <= 1000; i++)
            data.put(
                    (long) i,
                    new DomainLedger.Cursor(
                            i,
                            new ResourceVariantKey(
                                    ResourceLocation.parse("minecraft:item"), new byte[] {(byte) (i >> 8), (byte) i}),
                            i));
        long[] version = {0};
        int[] described = {0};
        var source = new DomainInventorySearch.Source() {
            public DomainLedger.Cursor after(long cursor, long ceiling) {
                var next = data.higherEntry(cursor);
                return next == null || next.getKey() > ceiling ? null : next.getValue();
            }

            public long maximumId() {
                return data.lastKey();
            }

            public long version() {
                return version[0];
            }
        };
        var search = new DomainInventorySearch(source, entry -> {
            described[0]++;
            return new DomainInventoryQuery.Document(
                    "iron", "minecraft", "minecraft:iron", "minecraft:item", List.of(), () -> "");
        });
        search.tick(32, () -> true, false);
        assertEquals(32, described[0]);
        assertTrue(search.ids().isEmpty());
        for (int i = 0; i < 100; i++) search.tick(32, () -> true, false);
        assertEquals(1000, search.ids().size());
        assertEquals(1, search.ids().getFirst());
        var previous = search.ids();
        assertFalse(search.query("iron |"));
        assertTrue(search.invalid());
        assertSame(previous, search.ids());
        assertTrue(search.query("iron"));
        search.sort(DomainInventorySearch.Sort.AMOUNT_DESC);
        for (int i = 0; i < 100; i++) search.tick(32, () -> true, true);
        assertSame(previous, search.ids());
        search.tick(1, () -> true, false);
        assertEquals(1000, search.ids().getFirst());
        int beforeRefresh = described[0];
        search.refresh();
        search.tick(32, () -> true, false);
        assertEquals(beforeRefresh + 32, described[0]);
        search.close();
        assertTrue(search.ids().isEmpty());
    }

    @Test
    void equalNamesUseStableKeysAndAmountSortingKeepsResourceTypesSeparate() {
        var data = new TreeMap<Long, DomainLedger.Cursor>();
        data.put(
                1L,
                new DomainLedger.Cursor(
                        1, new ResourceVariantKey(ResourceLocation.parse("minecraft:item"), new byte[] {2}), 1));
        data.put(
                2L,
                new DomainLedger.Cursor(
                        2, new ResourceVariantKey(ResourceLocation.parse("minecraft:item"), new byte[] {1}), 2));
        data.put(
                3L,
                new DomainLedger.Cursor(
                        3,
                        new ResourceVariantKey(ResourceLocation.parse("minecraft:fluid"), new byte[] {1}),
                        Long.MAX_VALUE));
        long[] revision = {0};
        var source = new DomainInventorySearch.Source() {
            public DomainLedger.Cursor after(long cursor, long ceiling) {
                var next = data.higherEntry(cursor);
                return next == null ? null : next.getValue();
            }

            public long maximumId() {
                return 3;
            }

            public long version() {
                return revision[0];
            }
        };
        var search = new DomainInventorySearch(
                source,
                entry -> new DomainInventoryQuery.Document(
                        "same", "same", "same", entry.key().typeId().toString(), List.of(), () -> ""));
        search.tick(100, () -> true, false);
        assertEquals(List.of(3L, 2L, 1L), search.ids());
        long display = search.displayVersion();
        revision[0]++;
        search.tick(100, () -> true, false);
        assertEquals(display, search.displayVersion());
        search.sort(DomainInventorySearch.Sort.AMOUNT_DESC);
        search.tick(100, () -> true, false);
        assertEquals(List.of(2L, 1L, 3L), search.ids());
    }
}
