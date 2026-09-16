// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.OnlinePlayerPage;
import io.github.loongin.omniresonance.networking.OnlinePlayerSummary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.jetbrains.annotations.Nullable;

/** One selection view's bounded 262144-entry snapshot; reset on leaving, replacement, disconnect or revocation. */
final class OnlinePlayerCatalog implements ClientTextSearch.Catalog<OnlinePlayerSummary> {
    private final ClientCatalogWindow window = new ClientCatalogWindow(262144, 128);
    private final List<OnlinePlayerSummary> entries = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final Set<UUID> seen = new HashSet<>();
    private @Nullable UUID snapshotId;
    private int total;
    private boolean ready;
    private boolean failed;
    private @Nullable String lastQuery;
    private long lastRevision = -1;
    private List<OnlinePlayerSummary> filtered = List.of();

    boolean begin(OnlinePlayerPage page) {
        reset();
        snapshotId = page.snapshotId();
        total = page.totalCount();
        return append(page);
    }

    boolean append(OnlinePlayerPage page) {
        if (ready
                || failed
                || !page.snapshotId().equals(snapshotId)
                || !window.accept(
                        page.snapshotId(),
                        page.offset(),
                        page.totalCount(),
                        page.entries().size())) {
            fail();
            return false;
        }
        for (OnlinePlayerSummary player : page.entries()) {
            if (!seen.add(player.playerId())) {
                fail();
                return false;
            }
            entries.add(player);
            names.add(ClientTextSearch.fold(player.name()));
        }
        ready = entries.size() == total;
        if (ready) seen.clear();
        return true;
    }

    boolean ready() {
        return ready;
    }

    boolean failed() {
        return failed;
    }

    int received() {
        return entries.size();
    }

    int total() {
        return total;
    }

    List<OnlinePlayerSummary> filter(String query) {
        return ClientTextSearch.filter(this, query);
    }

    @Override
    public List<OnlinePlayerSummary> filter(String query, BiPredicate<String, String> matcher, long matcherRevision) {
        if (!ready) return List.of();
        String folded = ClientTextSearch.fold(query);
        if (folded.equals(lastQuery) && matcherRevision == lastRevision) return filtered;
        List<OnlinePlayerSummary> result = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            if (folded.isEmpty() || matcher.test(names.get(index), folded)) result.add(entries.get(index));
        }
        List<OnlinePlayerSummary> completed = List.copyOf(result);
        lastQuery = folded;
        lastRevision = matcherRevision;
        filtered = completed;
        return completed;
    }

    void reset() {
        window.reset();
        entries.clear();
        names.clear();
        seen.clear();
        snapshotId = null;
        total = 0;
        ready = false;
        failed = false;
        lastQuery = null;
        lastRevision = -1;
        filtered = List.of();
    }

    void fail() {
        reset();
        failed = true;
    }
}
