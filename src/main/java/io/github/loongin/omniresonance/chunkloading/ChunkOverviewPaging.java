// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.chunkloading;

import java.util.List;
import java.util.function.ToLongFunction;

/** Binary cursor lookup in an immutable stable node-number snapshot; no offset scan or mutation. */
final class ChunkOverviewPaging {
    private ChunkOverviewPaging() {}

    record Window(int start, int end) {}

    static Window window(int size, int bound, boolean before) {
        int start = before ? Math.max(0, bound - 64) : bound;
        int end = before ? bound : Math.min(size, start + 64);
        if (size > 0 && start == end) {
            start = before ? 0 : Math.max(0, size - 64);
            end = before ? Math.min(size, 64) : size;
        }
        return new Window(start, end);
    }

    static <T> int bound(List<T> rows, long anchor, boolean before, ToLongFunction<T> number) {
        int low = 0, high = rows.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            long key = number.applyAsLong(rows.get(middle));
            if (key < anchor || !before && key == anchor) low = middle + 1;
            else high = middle;
        }
        return low;
    }
}
