// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Local scope/type picker with shared collapsed search, coalesced filtering and O(visible rows) scrolling. */
final class NodeResourceTypeSelection {
    private final NodeResourcePolicyDraft draft;
    private final @Nullable NodeResourceScopeDraft scope;
    private final Function<ResourceLocation, String> displayName;
    private final ClientSearchState search = new ClientSearchState();
    private List<ResourceLocation> results = List.of();
    private int scroll;
    private @Nullable ResourceLocation chosen;

    @Nullable
    ResourceLocation chosen() {
        return chosen;
    }

    private NodeResourceTypeSelection(
            NodeResourcePolicyDraft draft,
            @Nullable NodeResourceScopeDraft scope,
            Function<ResourceLocation, String> displayName) {
        this.draft = Objects.requireNonNull(draft, "draft");
        this.scope = scope;
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        refresh();
    }

    static NodeResourceTypeSelection scope(
            NodeResourcePolicyDraft draft,
            NodeResourceScopeDraft scope,
            Function<ResourceLocation, String> displayName) {
        Objects.requireNonNull(scope, "scope").requireOwner(draft);
        return new NodeResourceTypeSelection(draft, scope, displayName);
    }

    static NodeResourceTypeSelection overrides(
            NodeResourcePolicyDraft draft, Function<ResourceLocation, String> displayName) {
        return new NodeResourceTypeSelection(draft, null, displayName);
    }

    ClientSearchState search() {
        return search;
    }

    @Nullable
    NodeResourceScopeDraft scope() {
        return scope;
    }

    List<ResourceLocation> results() {
        return results;
    }

    int scroll() {
        return scroll;
    }

    boolean unavailable(ResourceLocation id) {
        return draft.catalog.find(id) == null;
    }

    void editSearch(String value, long nowTick) {
        search.edit(value, nowTick);
    }

    boolean tick(long nowTick) {
        if (!search.due(nowTick)) return false;
        refresh();
        search.handled();
        return true;
    }

    boolean closeSearch(long nowTick) {
        if (!search.close(nowTick)) return false;
        refresh();
        search.handled();
        return true;
    }

    void choose(ResourceLocation id) {
        chosen = id;
        if (scope != null) scope.toggle(id);
        else {
            draft.addType(id);
            refresh();
        }
    }

    void viewport(int visibleRows) {
        scroll = Math.min(scroll, Math.max(0, results.size() - Math.max(1, visibleRows)));
    }

    void wheel(double amount, int visibleRows) {
        viewport(visibleRows);
        scroll = PagedListScroll.navigate(scroll, results.size(), Math.max(1, visibleRows), false, false, amount)
                .scroll();
    }

    private void refresh() {
        String query = ClientTextSearch.fold(search.draft());
        var matches = new ArrayList<ResourceLocation>();
        if (scope != null) {
            for (ResourceLocation id : scope.choices()) if (matches(id, query)) matches.add(id);
        } else {
            for (var descriptor : draft.catalog.entries()) {
                ResourceLocation id = descriptor.typeId();
                if (draft.includes(id) && !draft.hasSetting(id) && matches(id, query)) matches.add(id);
            }
        }
        results = List.copyOf(matches);
        scroll = 0;
    }

    private boolean matches(ResourceLocation id, String query) {
        return query.isEmpty()
                || id.toString().contains(query)
                || ClientTextSearch.fold(displayName.apply(id)).contains(query);
    }
}
