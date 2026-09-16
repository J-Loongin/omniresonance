// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.FilterPresetPage;
import io.github.loongin.omniresonance.networking.FilterPresetSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PresetSearchCatalogTest {
    static FilterPresetPage batch(int offset, int total, long revision) {
        var rows = new ArrayList<FilterPresetSummary>();
        for (int i = offset; i < Math.min(offset + 128, total); i++)
            rows.add(new FilterPresetSummary(new UUID(0, i + 1), i == 169 ? "测试预设" : "Preset " + i, 0, 0, true));
        return new FilterPresetPage(rows, offset, total, revision);
    }

    @Test
    void completeCatalogSupportsCrossBatchMatchingAndMatcherChanges() {
        var catalog = new PresetSearchCatalog();
        catalog.accept(batch(0, 260, 4));
        assertFalse(catalog.ready());
        assertTrue(catalog.matches("").isEmpty());
        catalog.accept(batch(128, 260, 4));
        catalog.accept(batch(256, 260, 4));
        assertTrue(catalog.ready());
        assertEquals(260, catalog.matches("").size());
        try {
            ClientTextSearch.install(
                    (name, query) -> name.contains(query) || name.equals("测试预设") && query.equals("csys"));
            assertEquals(1, catalog.matches("csys").size());
            ClientTextSearch.usePlain();
            assertTrue(catalog.matches("csys").isEmpty());
            assertEquals(1, catalog.matches("测试").size());
        } finally {
            ClientTextSearch.usePlain();
        }
    }

    @Test
    void inconsistentRevisionOrNonProgressingBatchDiscardsPartialData() {
        var catalog = new PresetSearchCatalog();
        catalog.accept(batch(0, 260, 4));
        catalog.accept(batch(128, 260, 5));
        assertTrue(catalog.failed());
        assertTrue(catalog.matches("").isEmpty());
        catalog.clear();
        catalog.accept(new FilterPresetPage(List.of(), 0, 1, 2));
        assertTrue(catalog.failed());
        catalog.clear();
        catalog.accept(batch(0, 0, 3));
        assertTrue(catalog.ready());
    }
}
