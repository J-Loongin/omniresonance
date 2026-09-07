// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.NodeTunnelSummary;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class ClientTextSearchTest {
    @AfterEach
    void restoreTheDefaultMatcher() {
        ClientTextSearch.usePlain();
    }

    @Test
    void defaultSearchWorksWithoutAnyOptionalModAndReplacementInvalidatesCachedResults() {
        ClientTextSearch.usePlain();
        NodeTunnelCatalog catalog = new NodeTunnelCatalog();
        catalog.begin(NodeTunnelCatalogTest.batch(1, 2, 2, 0));
        assertFalse(ClientTextSearch.failed());
        assertEquals(1, ClientTextSearch.filter(catalog, "TUNNEL 2").size());
        ClientTextSearch.install((name, query) -> true);
        assertEquals(2, ClientTextSearch.filter(catalog, "TUNNEL 2").size());
    }

    @Test
    void optionalFailureRecomputesTheWholeResultWithPlainMatchingAndDoesNotRetryTheBrokenMatcher() {
        NodeTunnelCatalog catalog = new NodeTunnelCatalog();
        catalog.begin(NodeTunnelCatalogTest.batch(1, 2, 2, 0));
        AtomicInteger calls = new AtomicInteger();
        ClientTextSearch.install((name, query) -> {
            if (calls.incrementAndGet() == 1) {
                return true;
            }
            throw new IllegalStateException("Deliberate optional-matcher failure");
        });
        List<NodeTunnelSummary> result = ClientTextSearch.filter(catalog, "Tunnel 2");
        assertEquals(1, result.size());
        assertEquals("Tunnel 2", result.getFirst().name());
        assertTrue(ClientTextSearch.failed());
        assertSame(result, ClientTextSearch.filter(catalog, "Tunnel 2"));
        assertEquals(2, calls.get());
    }
}
