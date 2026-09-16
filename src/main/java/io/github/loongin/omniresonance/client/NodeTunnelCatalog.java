// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NodeMenuNodeSummary;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import io.github.loongin.omniresonance.networking.NodeTunnelPage;
import io.github.loongin.omniresonance.networking.NodeTunnelSummary;
import io.github.loongin.omniresonance.node.NodeMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.jetbrains.annotations.Nullable;

/**
 * One client Menu's catalog, bounded by the wire's 65535-tunnel limit. Identity/revision changes invalidate it;
 * closing the owning Screen releases it. Only complete catalogs are searchable and no world objects are retained.
 */
final class NodeTunnelCatalog implements ClientTextSearch.Catalog<NodeTunnelSummary> {
    private final ClientCatalogWindow window = new ClientCatalogWindow(65535, 128);
    private final List<NodeTunnelSummary> entries = new ArrayList<>();
    private final List<String> foldedNames = new ArrayList<>();
    private final Set<UUID> seen = new HashSet<>();
    private @Nullable UUID networkId;
    private @Nullable UUID nodeId;
    private long revision = -1;
    private int total;
    private boolean ready;
    private boolean failed;
    private @Nullable String lastQuery;
    private long lastMatcherRevision = -1;
    private List<NodeTunnelSummary> filtered = List.of();

    boolean begin(NodeMenuState.DirectTunnelList batch) {
        Objects.requireNonNull(batch, "batch");
        if (!batch.node().enabled()
                || batch.node().mode() != NodeMode.DIRECT
                || batch.page().hasPrevious()) {
            fail();
            return false;
        }
        if (ready
                && sameIdentity(batch)
                && total == batch.page().totalCount()
                && !batch.page().hasPrevious()) {
            return true;
        }
        reset();
        networkId = batch.node().networkId();
        nodeId = batch.node().nodeId();
        revision = batch.revision();
        total = batch.page().totalCount();
        return append(batch);
    }

    boolean append(NodeMenuState.DirectTunnelList batch) {
        Objects.requireNonNull(batch, "batch");
        NodeTunnelPage page = batch.page();
        int nextSize = entries.size() + page.entries().size();
        if (ready
                || failed
                || !sameIdentity(batch)
                || !batch.node().enabled()
                || batch.node().mode() != NodeMode.DIRECT
                || !window.accept(
                        List.of(batch.node().networkId(), batch.node().nodeId(), batch.revision()),
                        entries.size(),
                        page.totalCount(),
                        page.entries().size())
                || page.hasPrevious() != !entries.isEmpty()
                || nextSize > total
                || page.hasNext() != (nextSize < total)
                || (page.entries().isEmpty() && total != 0)) {
            fail();
            return false;
        }
        for (NodeTunnelSummary entry : page.entries()) {
            if (!seen.add(entry.tunnelId())) {
                fail();
                return false;
            }
            entries.add(entry);
            foldedNames.add(ClientTextSearch.fold(entry.name()));
        }
        ready = entries.size() == total;
        if (ready) {
            seen.clear();
        }
        return true;
    }

    boolean belongsTo(NodeMenuNodeSummary node) {
        return Objects.equals(networkId, node.networkId()) && Objects.equals(nodeId, node.nodeId());
    }

    private boolean sameIdentity(NodeMenuState.DirectTunnelList batch) {
        return belongsTo(batch.node()) && revision == batch.revision();
    }

    @Override
    public List<NodeTunnelSummary> filter(String query, BiPredicate<String, String> matcher, long matcherRevision) {
        Objects.requireNonNull(matcher, "matcher");
        String folded = ClientTextSearch.fold(query);
        if (!ready) {
            return List.of();
        }
        if (folded.equals(lastQuery) && lastMatcherRevision == matcherRevision) {
            return filtered;
        }
        List<NodeTunnelSummary> matches = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            if (folded.isEmpty() || matcher.test(foldedNames.get(index), folded)) {
                matches.add(entries.get(index));
            }
        }
        List<NodeTunnelSummary> result = List.copyOf(matches);
        lastQuery = folded;
        lastMatcherRevision = matcherRevision;
        filtered = result;
        return result;
    }

    boolean ready() {
        return ready;
    }

    boolean loading() {
        return revision >= 0 && !ready && !failed;
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

    UUID nextAnchor() {
        if (!loading() || entries.isEmpty()) {
            throw new IllegalStateException("Catalog has no continuation");
        }
        return entries.getLast().tunnelId();
    }

    void fail() {
        reset();
        failed = true;
    }

    void reset() {
        window.reset();
        entries.clear();
        foldedNames.clear();
        seen.clear();
        networkId = null;
        nodeId = null;
        revision = -1;
        total = 0;
        ready = false;
        failed = false;
        lastQuery = null;
        lastMatcherRevision = -1;
        filtered = List.of();
    }
}
