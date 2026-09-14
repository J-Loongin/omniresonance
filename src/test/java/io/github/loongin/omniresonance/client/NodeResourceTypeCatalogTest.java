// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.ResourceTypeCatalogPage;
import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NodeResourceTypeCatalogTest {
    private static final UUID SESSION = new UUID(1, 1), CATALOG = new UUID(2, 2);

    @Test
    void manyPagesVisitOnlyMetadataAndPreserveFullCatalogOrder() {
        var directory = new ResourceAdapterDirectory(4096);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        for (int index = 0; index < 4096; index++) {
            directory.register(
                    new ResourceAdapterDirectory.Descriptor(
                            net.minecraft.resources.ResourceLocation.parse("test:type_" + index), "FE", 123),
                    net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                    (handler, registries) -> {
                        calls.incrementAndGet();
                        return new io.github.loongin.omniresonance.transfer.EnergyResourcePort(handler);
                    });
        }
        directory.freeze();
        var catalog = new NodeResourceTypeCatalog();
        catalog.open(SESSION);
        int pages = 0;
        while (!catalog.ready()) {
            var request = catalog.nextRequest();
            var page = ResourceTypeCatalogPage.from(directory, CATALOG, request.offset(), 262144, 100);
            assertTrue(page.encodedSize() + 100 <= 262144);
            assertTrue(catalog.complete(request, page));
            pages++;
        }
        assertEquals(32, pages);
        assertEquals(4096, catalog.snapshot().entries().size());
        assertEquals(
                "test:type_4095",
                catalog.snapshot().entries().getLast().typeId().toString());
        assertEquals(0, calls.get());
    }

    @Test
    void singleInflightCatalogRejectsOldRepliesAndPublishesOnlyCompleteSnapshot() {
        var full = ResourceTypeCatalogPage.from(ResourceAdapterDirectory.nativeDefaults(), CATALOG, 0, 262144, 100);
        var catalog = new NodeResourceTypeCatalog();
        catalog.open(SESSION);
        var first = catalog.nextRequest();
        assertNull(catalog.nextRequest());
        assertThrows(IllegalStateException.class, catalog::snapshot);
        assertTrue(catalog.complete(
                first, new ResourceTypeCatalogPage(CATALOG, 0, 3, full.entries().subList(0, 1))));
        assertFalse(catalog.ready());
        var second = catalog.nextRequest();
        assertEquals(1, second.offset());
        assertFalse(catalog.complete(first, full));
        assertNull(catalog.nextRequest());
        assertTrue(catalog.complete(
                second,
                new ResourceTypeCatalogPage(CATALOG, 1, 3, full.entries().subList(1, 3))));
        assertTrue(catalog.ready());
        assertSame(catalog.snapshot(), catalog.snapshot());
        assertEquals(3, catalog.snapshot().entries().size());
        catalog.close();
        assertFalse(catalog.ready());
        assertFalse(catalog.complete(second, full));
        assertThrows(IllegalStateException.class, catalog::snapshot);
    }

    @Test
    void duplicateAcrossPagesOrChangedCatalogFailsInsteadOfPublishingPartialTypes() {
        var full = ResourceTypeCatalogPage.from(ResourceAdapterDirectory.nativeDefaults(), CATALOG, 0, 262144, 100);
        var catalog = new NodeResourceTypeCatalog();
        catalog.open(SESSION);
        catalog.complete(
                catalog.nextRequest(),
                new ResourceTypeCatalogPage(CATALOG, 0, 3, full.entries().subList(0, 1)));
        assertFalse(catalog.complete(
                catalog.nextRequest(),
                new ResourceTypeCatalogPage(
                        CATALOG, 1, 3, List.of(full.entries().getFirst()))));
        assertTrue(catalog.failed());
        assertNull(catalog.nextRequest());
        assertEquals(0, catalog.received());
        catalog.open(SESSION);
        catalog.complete(
                catalog.nextRequest(),
                new ResourceTypeCatalogPage(CATALOG, 0, 3, full.entries().subList(0, 1)));
        assertFalse(catalog.complete(
                catalog.nextRequest(),
                new ResourceTypeCatalogPage(new UUID(5, 5), 1, 3, full.entries().subList(1, 3))));
        assertTrue(catalog.failed());
    }
}
