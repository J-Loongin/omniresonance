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
