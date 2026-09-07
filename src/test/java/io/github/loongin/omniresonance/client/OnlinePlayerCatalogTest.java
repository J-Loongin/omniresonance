// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.OnlinePlayerPage;
import io.github.loongin.omniresonance.networking.OnlinePlayerSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OnlinePlayerCatalogTest {
    private static final UUID SNAPSHOT = new UUID(830, 1);

    @AfterEach
    void resetMatcher() {
        ClientTextSearch.usePlain();
    }

    @Test
    void candidateSearchWaitsForTheWholeSnapshotAndReusesUnchangedQueries() {
        OnlinePlayerCatalog catalog = new OnlinePlayerCatalog();
        assertTrue(catalog.begin(page(SNAPSHOT, 0, 128, 300)));
        assertFalse(catalog.ready());
        assertTrue(catalog.filter("Player").isEmpty());
        assertTrue(catalog.append(page(SNAPSHOT, 128, 128, 300)));
        assertTrue(catalog.append(page(SNAPSHOT, 256, 44, 300)));
        assertTrue(catalog.ready());
        AtomicInteger calls = new AtomicInteger();
        ClientTextSearch.install((name, query) -> {
            calls.incrementAndGet();
            return name.contains(query);
        });
        assertEquals("Player 299", catalog.filter("PLAYER 299").getFirst().name());
        for (int i = 0; i < 100; i++)
            assertEquals(1, catalog.filter("player 299").size());
        assertEquals(300, calls.get());
    }

    @Test
    void wrongEpochGapsAndDuplicatesDiscardPartialCandidates() {
        OnlinePlayerCatalog catalog = new OnlinePlayerCatalog();
        catalog.begin(page(SNAPSHOT, 0, 128, 300));
        assertFalse(catalog.append(page(new UUID(830, 2), 128, 128, 300)));
        assertTrue(catalog.failed());
        catalog.begin(page(SNAPSHOT, 0, 128, 300));
        assertFalse(catalog.append(page(SNAPSHOT, 129, 128, 300)));
        catalog.begin(page(SNAPSHOT, 0, 128, 300));
        var duplicate =
                new OnlinePlayerPage(SNAPSHOT, page(SNAPSHOT, 0, 128, 300).entries(), 128, 300, true);
        assertFalse(catalog.append(duplicate));
        assertTrue(catalog.filter("").isEmpty());
        catalog.begin(page(SNAPSHOT, 0, 0, 0));
        assertTrue(catalog.ready());
        catalog.reset();
        assertFalse(catalog.ready());
    }

    @Test
    void jecFailureRecomputesCandidateResultsWithPlainMatching() {
        OnlinePlayerCatalog catalog = new OnlinePlayerCatalog();
        catalog.begin(page(SNAPSHOT, 0, 3, 3));
        AtomicInteger calls = new AtomicInteger();
        ClientTextSearch.install((name, query) -> {
            if (calls.incrementAndGet() == 2) throw new LinkageError("Absent optional class");
            return false;
        });
        assertEquals(3, catalog.filter("player").size());
        assertTrue(ClientTextSearch.failed());
        assertEquals(2, calls.get());
    }

    private static OnlinePlayerPage page(UUID snapshot, int offset, int size, int total) {
        List<OnlinePlayerSummary> players = new ArrayList<>();
        for (int index = offset; index < offset + size; index++)
            players.add(new OnlinePlayerSummary(new UUID(831, index), "Player " + index));
        return new OnlinePlayerPage(snapshot, players, offset, total, offset + size < total);
    }
}
