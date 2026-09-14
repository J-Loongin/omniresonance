// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.FilterPresetPage;
import org.jetbrains.annotations.Nullable;

/** Screen-owned single-page cache: replaced by query/page changes and released when the picker closes. */
final class NodePresetPicker {
    record Request(
            String query, int offset, long libraryRevision, long generation, PagedListScroll.PageRequest direction) {}

    private final ClientSearchState search = new ClientSearchState();
    private long generation;
    private boolean open;
    private int scroll;
    private int visibleRows = 1;
    private @Nullable FilterPresetPage page;
    private @Nullable Request queued;
    private @Nullable Request pending;

    void open() {
        open = true;
        search.reset();
        restart();
    }

    void close() {
        open = false;
        search.reset();
        generation++;
        queued = null;
        page = null;
        scroll = 0;
    }

    String query() {
        return search.draft();
    }

    @Nullable
    FilterPresetPage page() {
        return page;
    }

    int scroll() {
        return scroll;
    }

    boolean pending() {
        return pending != null;
    }

    boolean ready() {
        return open && page != null && pending == null && queued == null;
    }

    int count() {
        return page == null ? 0 : page.entries().size() + (page.offset() == 0 ? 1 : 0);
    }

    ClientSearchState search() {
        return search;
    }

    boolean closeSearch() {
        if (!search.close(0)) return false;
        restart();
        return true;
    }

    void edit(String value) {
        if (search.expanded() && !search.draft().equals(value)) {
            search.edit(value, 0);
            restart();
        }
    }

    private void restart() {
        generation++;
        page = null;
        scroll = 0;
        queued = new Request(query(), 0, -1, generation, PagedListScroll.PageRequest.NONE);
    }

    @Nullable
    Request nextRequest() {
        if (!open || pending != null || queued == null) return null;
        search.handled();
        pending = queued;
        queued = null;
        return pending;
    }

    boolean complete(@Nullable FilterPresetPage result) {
        Request request = pending;
        pending = null;
        if (request == null || !open || request.generation() != generation) return false;
        page = result;
        scroll = result != null
                        && request.direction() == PagedListScroll.PageRequest.PREVIOUS
                        && result.offset() == request.offset()
                        && result.libraryRevision() == request.libraryRevision()
                ? Math.max(0, count() - visibleRows)
                : 0;
        return true;
    }

    void viewport(int rows) {
        visibleRows = Math.max(1, rows);
        scroll = Math.min(scroll, Math.max(0, count() - visibleRows));
    }

    void wheel(double amount, int rows) {
        viewport(rows);
        if (!ready()) return;
        var result = PagedListScroll.navigate(scroll, count(), visibleRows, page.offset() > 0, page.hasNext(), amount);
        scroll = result.scroll();
        if (result.pageRequest() != PagedListScroll.PageRequest.NONE) {
            int offset = result.pageRequest() == PagedListScroll.PageRequest.PREVIOUS
                    ? Math.max(0, page.offset() - FilterPresetPage.MAXIMUM_ENTRIES)
                    : page.offset() + page.entries().size();
            queued = new Request(query(), offset, page.libraryRevision(), generation, result.pageRequest());
        }
    }
}
