// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.chunkloading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkOverviewPagingTest {
    @Test
    void deletedEdgeAnchorsFallBackToReachableWindows() {
        assertEquals(new ChunkOverviewPaging.Window(6, 70), ChunkOverviewPaging.window(70, 70, false));
        assertEquals(new ChunkOverviewPaging.Window(0, 64), ChunkOverviewPaging.window(70, 0, true));
        assertEquals(new ChunkOverviewPaging.Window(0, 0), ChunkOverviewPaging.window(0, 0, false));
    }

    @Test
    void cursorWindowsResumeBothDirectionsAndHandleDeletedAnchors() {
        List<Long> values = List.of(1L, 3L, 5L, 7L, 9L);
        assertEquals(2, ChunkOverviewPaging.bound(values, 4, true, Long::longValue));
        assertEquals(2, ChunkOverviewPaging.bound(values, 3, false, Long::longValue));
        assertEquals(1, ChunkOverviewPaging.bound(values, 3, true, Long::longValue));
        assertEquals(0, ChunkOverviewPaging.bound(values, 0, false, Long::longValue));
        assertEquals(5, ChunkOverviewPaging.bound(values, Long.MAX_VALUE, false, Long::longValue));
    }
}
