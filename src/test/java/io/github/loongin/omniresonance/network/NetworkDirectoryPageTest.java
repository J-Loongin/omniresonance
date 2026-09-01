// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class NetworkDirectoryPageTest {
    private static final UUID OWNER = new UUID(1, 1);
    private static final UUID ADMIN = new UUID(1, 2);

    @Test
    void walksOrderedPagesInBothDirectionsWithoutSharingMutableLists() {
        NetworkDirectory directory = directory(5);
        NetworkDirectory.AccessPage first = directory.pageAccessible(ADMIN, null, false, 2);
        assertEquals(List.of(new UUID(0, 0), new UUID(0, 1)), ids(first));
        assertEquals(5, first.totalCount());
        assertFalse(first.hasPrevious());
        assertTrue(first.hasNext());
        NetworkDirectory.AccessPage second = directory.pageAccessible(ADMIN, new UUID(0, 1), false, 2);
        assertEquals(List.of(new UUID(0, 2), new UUID(0, 3)), ids(second));
        assertTrue(second.hasPrevious());
        assertTrue(second.hasNext());
        assertEquals(first, directory.pageAccessible(ADMIN, new UUID(0, 2), true, 2));
        NetworkDirectory.AccessPage last = directory.pageAccessible(ADMIN, new UUID(0, 3), false, 2);
        assertEquals(List.of(new UUID(0, 4)), ids(last));
        assertTrue(last.hasPrevious());
        assertFalse(last.hasNext());
        assertThrows(UnsupportedOperationException.class, () -> first.entries().clear());
        assertEquals(5, directory.ownedCount(OWNER));
        assertEquals(0, directory.ownedCount(ADMIN));
    }

    @Test
    void refusesUnauthorizedAnchorsInvalidBoundsAndWrongThread() throws Exception {
        NetworkDirectory directory = directory(3);
        assertThrows(
                IllegalArgumentException.class,
                () -> directory.pageAccessible(new UUID(9, 9), new UUID(0, 0), false, 2));
        assertThrows(IllegalArgumentException.class, () -> directory.pageAccessible(ADMIN, null, true, 2));
        for (int limit : List.of(0, -1, 2049)) {
            assertThrows(IllegalArgumentException.class, () -> directory.pageAccessible(ADMIN, null, false, limit));
        }
        try (var executor = Executors.newSingleThreadExecutor()) {
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> executor.submit(() -> directory.pageAccessible(ADMIN, null, false, 2))
                            .get());
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(
                    assertThrows(
                                            ExecutionException.class,
                                            () -> executor.submit(() -> directory.ownedCount(OWNER))
                                                    .get())
                                    .getCause()
                            instanceof IllegalStateException);
            assertTrue(
                    assertThrows(
                                            ExecutionException.class,
                                            () -> executor.submit(() -> directory.firstOwned(OWNER))
                                                    .get())
                                    .getCause()
                            instanceof IllegalStateException);
        }
    }

    @Test
    void tenThousandEntriesReturnOnlyRequestedWindow() {
        NetworkDirectory directory = directory(10000);
        NetworkDirectory.AccessPage page = directory.pageAccessible(ADMIN, new UUID(0, 4999), false, 3);
        assertEquals(List.of(new UUID(0, 5000), new UUID(0, 5001), new UUID(0, 5002)), ids(page));
        assertEquals(10000, page.totalCount());
        assertTrue(page.hasNext());
        assertTrue(page.hasPrevious());
        assertEquals(
                List.of(),
                directory.pageAccessible(new UUID(9, 9), null, false, 2).entries());
    }

    @Test
    void firstOwnedReturnsEarliestEntryWithoutExposingACollection() {
        NetworkDirectory directory = directory(3);

        assertEquals(new UUID(0, 0), directory.firstOwned(OWNER).orElseThrow().id());
        assertTrue(directory.firstOwned(ADMIN).isEmpty());
    }

    private static NetworkDirectory directory(int count) {
        List<NetworkMetadata> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(new NetworkMetadata(new UUID(0, i), OWNER, new ManagedName("Network " + i), i, Set.of(ADMIN)));
        }
        return new NetworkDirectory(entries);
    }

    private static List<UUID> ids(NetworkDirectory.AccessPage page) {
        return page.entries().stream().map(NetworkMetadata::id).toList();
    }
}
