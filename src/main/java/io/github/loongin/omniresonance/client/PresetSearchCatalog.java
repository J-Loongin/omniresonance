// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.FilterPresetPage;
import io.github.loongin.omniresonance.networking.FilterPresetSummary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Client-thread owner-library snapshot, bounded by the wire limit; cleared on scope exit, retry or invalidation. */
final class PresetSearchCatalog {
    private final ClientCatalogWindow window = new ClientCatalogWindow(262144, 128);
    private final ArrayList<FilterPresetSummary> entries = new ArrayList<>();
    private final ArrayList<String> names = new ArrayList<>();
    private final HashSet<UUID> ids = new HashSet<>();
    private int total = -1;
    private long revision = -1, matcherRevision = -1;
    private boolean failed;
    private String lastQuery;
    private List<FilterPresetSummary> matches = List.of();

    int received() {
        return entries.size();
    }

    long revision() {
        return revision;
    }

    boolean ready() {
        return !failed && total >= 0 && entries.size() == total;
    }

    boolean failed() {
        return failed;
    }

    void clear() {
        window.reset();
        entries.clear();
        names.clear();
        ids.clear();
        total = -1;
        revision = -1;
        failed = false;
        lastQuery = null;
        matches = List.of();
    }

    void fail() {
        clear();
        failed = true;
    }

    void accept(FilterPresetPage page) {
        if (failed
                || !window.accept(
                        page.libraryRevision(),
                        page.offset(),
                        page.totalCount(),
                        page.entries().size())) {
            fail();
            return;
        }
        total = page.totalCount();
        revision = page.libraryRevision();
        for (var entry : page.entries()) {
            if (!ids.add(entry.id())) {
                fail();
                return;
            }
            entries.add(entry);
            names.add(ClientTextSearch.fold(entry.name()));
        }
    }

    List<FilterPresetSummary> matches(String query) {
        if (!ready()) return List.of();
        String normalized = ClientSearchState.normalizedQuery(query);
        if (normalized == null) return List.of();
        String folded = ClientTextSearch.fold(normalized);
        if (folded.equals(lastQuery) && matcherRevision == ClientTextSearch.matcherRevision()) return matches;
        matches = ClientTextSearch.filter(
                (text, matcher, version) -> {
                    var result = new ArrayList<FilterPresetSummary>();
                    for (int i = 0; i < entries.size(); i++)
                        if (text.isEmpty() || matcher.test(names.get(i), text)) result.add(entries.get(i));
                    return List.copyOf(result);
                },
                folded);
        matcherRevision = ClientTextSearch.matcherRevision();
        lastQuery = folded;
        return matches;
    }

    FilterPresetPage page(String query, int offset) {
        var values = matches(query);
        int start = Math.min(Math.max(0, offset), Math.max(0, values.size() - 1));
        return new FilterPresetPage(
                values.subList(start, Math.min(values.size(), start + FilterPresetPage.MAXIMUM_ENTRIES)),
                start,
                values.size(),
                Math.max(0, revision));
    }
}
