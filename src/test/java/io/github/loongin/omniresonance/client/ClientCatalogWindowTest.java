// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientCatalogWindowTest {
    @Test
    void allCatalogsRejectRevisionChangesGapsOverflowAndMissingProgress() {
        for (int batch : new int[] {64, 128}) {
            var window = new ClientCatalogWindow(262144, batch);
            assertTrue(window.accept("snapshot", 0, batch + 1, batch));
            assertFalse(window.accept("other", batch, batch + 1, 1));
            window.reset();
            assertFalse(window.accept("snapshot", 1, 2, 1));
            window.reset();
            assertFalse(window.accept("snapshot", 0, 1, 0));
            window.reset();
            assertFalse(window.accept("snapshot", 0, 262145, batch));
            window.reset();
            assertFalse(window.accept("snapshot", 0, batch + 1, batch + 1));
            window.reset();
            assertTrue(window.accept("snapshot", 0, 0, 0));
            assertTrue(window.ready());
            assertFalse(window.accept("snapshot", 0, 0, 0));
        }
    }
}
