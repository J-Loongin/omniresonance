// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Pure wheel-navigation policy for a bounded client window backed by cursor pages. */
final class PagedListScroll {
    private PagedListScroll() {}

    static Result navigate(
            int currentScroll,
            int entryCount,
            int visibleRows,
            boolean hasPreviousPage,
            boolean hasNextPage,
            double wheelDelta) {
        if (currentScroll < 0 || entryCount < 0 || visibleRows < 1) {
            throw new IllegalArgumentException("Invalid paged-list scroll state");
        }
        int maximumScroll = Math.max(0, entryCount - visibleRows);
        int scroll = Math.min(currentScroll, maximumScroll);
        if (wheelDelta < 0) {
            if (scroll < maximumScroll) {
                return new Result(scroll + 1, PageRequest.NONE);
            }
            return new Result(scroll, entryCount > 0 && hasNextPage ? PageRequest.NEXT : PageRequest.NONE);
        }
        if (wheelDelta > 0) {
            if (scroll > 0) {
                return new Result(scroll - 1, PageRequest.NONE);
            }
            return new Result(scroll, entryCount > 0 && hasPreviousPage ? PageRequest.PREVIOUS : PageRequest.NONE);
        }
        return new Result(scroll, PageRequest.NONE);
    }

    enum PageRequest {
        NONE,
        PREVIOUS,
        NEXT
    }

    record Result(int scroll, PageRequest pageRequest) {
        Result {
            if (scroll < 0 || pageRequest == null) {
                throw new IllegalArgumentException("Invalid paged-list result");
            }
        }
    }
}
