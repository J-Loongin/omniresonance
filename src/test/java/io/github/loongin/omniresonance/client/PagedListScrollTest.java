// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class PagedListScrollTest {
    @Test
    void wheelMovesInsideTheCurrentServerPageBeforeRequestingAnother() {
        assertEquals(
                new PagedListScroll.Result(4, PagedListScroll.PageRequest.NONE),
                PagedListScroll.navigate(3, 20, 6, true, true, -1));
        assertEquals(
                new PagedListScroll.Result(2, PagedListScroll.PageRequest.NONE),
                PagedListScroll.navigate(3, 20, 6, true, true, 1));
    }

    @Test
    void wheelRequestsTheAdjacentServerPageOnlyAtAVisibleEdge() {
        assertEquals(
                new PagedListScroll.Result(14, PagedListScroll.PageRequest.NEXT),
                PagedListScroll.navigate(14, 20, 6, true, true, -1));
        assertEquals(
                new PagedListScroll.Result(0, PagedListScroll.PageRequest.PREVIOUS),
                PagedListScroll.navigate(0, 20, 6, true, true, 1));
    }

    @Test
    void wheelStaysAtTheBoundaryWhenNoAdjacentServerPageExists() {
        assertEquals(
                new PagedListScroll.Result(14, PagedListScroll.PageRequest.NONE),
                PagedListScroll.navigate(14, 20, 6, true, false, -1));
        assertEquals(
                new PagedListScroll.Result(0, PagedListScroll.PageRequest.NONE),
                PagedListScroll.navigate(0, 20, 6, false, true, 1));
    }
}
