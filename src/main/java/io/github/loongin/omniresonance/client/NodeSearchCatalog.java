// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NodeDirectoryPage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/** Client-thread, current-view catalog; bounded by the saved node hard limit and discarded on close or invalidation. */
final class NodeSearchCatalog {
    private final ClientCatalogWindow window = new ClientCatalogWindow(262144, 64);
    private final ArrayList<NodeDirectoryPage.Row> rows = new ArrayList<>();
    private final ArrayList<String> folded = new ArrayList<>();
    private final HashMap<UUID, Integer> identities = new HashMap<>();
    private long revision, matcherRevision = -1;
    private int total = -1;
    private String query;
    private List<NodeDirectoryPage.Row> result = List.of();

    int size() {
        return rows.size();
    }

    long revision() {
        return revision;
    }

    boolean ready() {
        return total >= 0 && rows.size() == total;
    }

    void clear() {
        window.reset();
        rows.clear();
        folded.clear();
        identities.clear();
        total = -1;
        revision = 0;
        query = null;
        result = List.of();
    }

    void accept(NodeDirectoryPage.Catalog batch, List<NodeDirectoryPage.Row> values) {
        if (!window.accept(batch.revision(), batch.offset(), batch.total(), values.size())) {
            clear();
            throw new IllegalArgumentException("Inconsistent node catalog");
        }
        revision = batch.revision();
        total = batch.total();
        for (var row : values) {
            if (identities.putIfAbsent(row.node().nodeId(), rows.size()) != null
                    || !rows.isEmpty() && rows.getLast().number() >= row.number()) {
                clear();
                throw new IllegalArgumentException("Duplicate or unordered node catalog");
            }
            rows.add(row);
            var p = row.node().position();
            folded.add(
                    ClientTextSearch.fold(row.node().nodeName() + "\n" + p.getX() + ", " + p.getY() + ", " + p.getZ()));
        }
    }

    void update(NodeDirectoryPage.Row row) {
        if (!ready()) return;
        Integer index = identities.get(row.node().nodeId());
        if (index == null) return;
        var previous = rows.get(index);
        if (row.node().revision() < previous.node().revision() || row.equals(previous)) return;
        rows.set(index, row);
        var p = row.node().position();
        folded.set(
                index,
                ClientTextSearch.fold(row.node().nodeName() + "\n" + p.getX() + ", " + p.getY() + ", " + p.getZ()));
        query = null;
        result = List.of();
    }

    List<NodeDirectoryPage.Row> filter(String input) {
        if (!ready()) return List.of();
        String next = ClientTextSearch.fold(input.strip());
        if (next.equals(query) && matcherRevision == ClientTextSearch.matcherRevision()) return result;
        result = ClientTextSearch.filter(
                (text, matcher, rev) -> {
                    var matches = new ArrayList<NodeDirectoryPage.Row>();
                    for (int i = 0; i < rows.size(); i++)
                        if (text.isEmpty() || matcher.test(folded.get(i), text)) matches.add(rows.get(i));
                    return List.copyOf(matches);
                },
                next);
        query = next;
        matcherRevision = ClientTextSearch.matcherRevision();
        return result;
    }
}
