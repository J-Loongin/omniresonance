// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.NodeMenuNodeSummary;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import io.github.loongin.omniresonance.networking.NodeTunnelPage;
import io.github.loongin.omniresonance.networking.NodeTunnelSummary;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class NodeTunnelCatalogTest {
    private static final UUID NETWORK = new UUID(620, 1);
    private static final UUID NODE = new UUID(620, 2);

    @Test
    void anotherNetworkOrNodeCannotAppendToTheCurrentTransfer() {
        for (NodeMenuNodeSummary foreign : List.of(node(new UUID(620, 8), NODE), node(NETWORK, new UUID(620, 9)))) {
            NodeTunnelCatalog catalog = new NodeTunnelCatalog();
            catalog.begin(batch(1, 128, 300, 4));
            assertFalse(catalog.append(new NodeMenuState.DirectTunnelList(
                    foreign, batch(129, 256, 300, 4).page(), 4)));
            assertTrue(catalog.failed());
            assertEquals(0, catalog.received());
        }
    }

    @Test
    void switchingScopeReplacesCompletedDataEvenWhenItsRevisionAndCountAreEqual() {
        NodeTunnelCatalog catalog = new NodeTunnelCatalog();
        catalog.begin(batch(1, 1, 1, 3));
        assertEquals(1, catalog.filter("Tunnel", String::contains, 0).size());
        NodeTunnelSummary foreign = new NodeTunnelSummary(new UUID(621, 900), "Other catalog", 0, true, 1, 0, 0);
        assertTrue(catalog.begin(new NodeMenuState.DirectTunnelList(
                node(new UUID(620, 8), NODE), new NodeTunnelPage(List.of(foreign), 1, false, false), 3)));
        assertTrue(catalog.filter("Tunnel", String::contains, 0).isEmpty());
        assertEquals(List.of(foreign), catalog.filter("Other", String::contains, 0));
    }

    @Test
    void disabledNodeCannotReuseAnOtherwiseMatchingCompleteCatalog() {
        NodeTunnelCatalog catalog = new NodeTunnelCatalog();
        NodeMenuState.DirectTunnelList first = batch(1, 2, 2, 4);
        catalog.begin(first);
        NodeMenuNodeSummary node = node();
        NodeMenuNodeSummary disabled = new NodeMenuNodeSummary(
                node.networkId(),
                node.networkName(),
                node.nodeId(),
                node.nodeName(),
                node.revision(),
                node.dimension(),
                node.position(),
                node.form(),
                node.facing(),
                false,
                node.chunkLoadingRequested(),
                node.mode());
        assertFalse(catalog.begin(new NodeMenuState.DirectTunnelList(disabled, first.page(), 4)));
        assertTrue(catalog.failed());
        assertTrue(catalog.filter("", String::contains, 0).isEmpty());
    }

    @Test
    void tenThousandNamesAreMatchedOncePerQueryAndNeverRescannedForUnchangedText() {
        NodeTunnelCatalog catalog = new NodeTunnelCatalog();
        catalog.begin(batch(1, 128, 10_000, 2));
        for (int from = 129; from <= 10_000; from += 128) {
            assertTrue(catalog.append(batch(from, Math.min(from + 127, 10_000), 10_000, 2)));
        }
        AtomicInteger calls = new AtomicInteger();
        BiPredicate<String, String> matcher = (name, query) -> {
            calls.incrementAndGet();
            return name.contains(query);
        };
        List<NodeTunnelSummary> result = catalog.filter("TUNNEL 10000", matcher, 1);
        assertEquals(1, result.size());
        assertEquals(new UUID(621, 10_000), result.getFirst().tunnelId());
        for (int repeat = 0; repeat < 100; repeat++) {
            assertSame(result, catalog.filter("tunnel 10000", matcher, 1));
        }
        assertEquals(10_000, calls.get());
    }

    @Test
    void rapidInputProducesOneLatestQueryScanAndIdleTicksDoNoWork() {
        NodeTunnelCatalog catalog = new NodeTunnelCatalog();
        catalog.begin(batch(1, 128, 10_000, 2));
        for (int from = 129; from <= 10_000; from += 128) {
            catalog.append(batch(from, Math.min(from + 127, 10_000), 10_000, 2));
        }
        NodeTunnelSearch search = new NodeTunnelSearch();
        search.open();
        for (int edit = 0; edit < 100; edit++) {
            search.edit("Tunnel " + edit, 10);
            assertFalse(search.due(10));
        }
        search.edit("Tunnel 10000", 10);
        assertTrue(search.due(11));
        AtomicInteger calls = new AtomicInteger();
        BiPredicate<String, String> matcher = (name, query) -> {
            calls.incrementAndGet();
            return name.contains(query);
        };
        assertEquals(
                "Tunnel 10000",
                catalog.filter(search.draft(), matcher, 0).getFirst().name());
        search.handled();
        for (long tick = 11; tick < 111; tick++) {
            assertFalse(search.due(tick));
        }
        assertEquals(10_000, calls.get(), "A single input batch must not scan once per edit");
    }

    @Test
    void fullCatalogFindsResultsBeyondTheFirstBatchAndCachesRepeatedQueries() {
        NodeTunnelCatalog catalog = new NodeTunnelCatalog();
        assertTrue(catalog.begin(batch(1, 128, 300, 9)));
        assertTrue(catalog.loading());
        assertEquals(new UUID(621, 128), catalog.nextAnchor());
        assertTrue(catalog.filter("末端", String::contains, 0).isEmpty());
        assertTrue(catalog.append(batch(129, 256, 300, 9)));
        assertTrue(catalog.append(batch(257, 300, 300, 9)));
        assertTrue(catalog.ready());
        assertEquals(300, catalog.received());
        assertEquals(
                "末端原木", catalog.filter("末端", String::contains, 0).getFirst().name());

        AtomicInteger calls = new AtomicInteger();
        BiPredicate<String, String> matcher = (name, query) -> {
            calls.incrementAndGet();
            return name.contains(query);
        };
        List<NodeTunnelSummary> first = catalog.filter("TUNNEL", matcher, 1);
        assertEquals(299, first.size());
        assertEquals(300, calls.get());
        assertSame(first, catalog.filter("tunnel", matcher, 1));
        assertEquals(300, calls.get());
        catalog.filter("tunnel", matcher, 2);
        assertEquals(600, calls.get());
    }

    @Test
    void matchingFirstBatchReusesACompleteCatalogButChangedRevisionRebuildsIt() {
        NodeTunnelCatalog catalog = new NodeTunnelCatalog();
        catalog.begin(batch(1, 2, 2, 4));
        List<NodeTunnelSummary> result = catalog.filter("", String::contains, 0);
        assertTrue(catalog.begin(batch(1, 2, 2, 4)));
        assertSame(result, catalog.filter("", String::contains, 0));
        assertTrue(catalog.begin(batch(1, 128, 300, 5)));
        assertFalse(catalog.ready());
        assertEquals(128, catalog.received());
    }

    @Test
    void changedRevisionOrDuplicateEntriesDiscardTheIncompleteCatalog() {
        NodeTunnelCatalog catalog = new NodeTunnelCatalog();
        catalog.begin(batch(1, 128, 300, 1));
        assertFalse(catalog.append(batch(129, 256, 300, 2)));
        assertTrue(catalog.failed());
        assertEquals(0, catalog.received());
        assertTrue(catalog.filter("", String::contains, 0).isEmpty());
        catalog.begin(batch(1, 128, 300, 3));
        assertFalse(catalog.append(batch(128, 255, 300, 3)));
        assertTrue(catalog.failed());
    }

    @Test
    void wrongTotalsAndEmptyContinuationCannotProducePartialSearchResults() {
        NodeTunnelCatalog catalog = new NodeTunnelCatalog();
        catalog.begin(batch(1, 128, 300, 1));
        assertFalse(catalog.append(batch(129, 256, 301, 1)));
        catalog.begin(batch(1, 128, 300, 1));
        assertFalse(catalog.append(
                new NodeMenuState.DirectTunnelList(node(), new NodeTunnelPage(List.of(), 300, true, true), 1)));
        assertTrue(catalog.failed());
    }

    @Test
    void emptyCatalogCompletesAndResetDropsAllMenuOwnedData() {
        NodeTunnelCatalog catalog = new NodeTunnelCatalog();
        assertTrue(catalog.begin(
                new NodeMenuState.DirectTunnelList(node(), new NodeTunnelPage(List.of(), 0, false, false), 0)));
        assertTrue(catalog.ready());
        catalog.reset();
        assertFalse(catalog.ready());
        assertFalse(catalog.loading());
        assertFalse(catalog.failed());
        assertEquals(0, catalog.received());
    }

    static NodeMenuState.DirectTunnelList batch(int from, int to, int total, long revision) {
        List<NodeTunnelSummary> entries = new ArrayList<>();
        for (int index = from; index <= to; index++) {
            entries.add(new NodeTunnelSummary(
                    new UUID(621, index), index == 300 ? "末端原木" : "Tunnel " + index, 0, true, 1, 0, 0));
        }
        return new NodeMenuState.DirectTunnelList(
                node(), new NodeTunnelPage(entries, total, from > 1, to < total), revision);
    }

    private static NodeMenuNodeSummary node() {
        return node(NETWORK, NODE);
    }

    private static NodeMenuNodeSummary node(UUID networkId, UUID nodeId) {
        return new NodeMenuNodeSummary(
                networkId,
                "Network",
                nodeId,
                "Node",
                0,
                ResourceLocation.withDefaultNamespace("overworld"),
                BlockPos.ZERO,
                NodeForm.BLOCK,
                Direction.NORTH,
                true,
                false,
                NodeMode.DIRECT);
    }
}
