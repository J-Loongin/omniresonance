// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Local scope/type picker with shared collapsed search, coalesced filtering and O(visible rows) scrolling. */
final class ResourceTypeSelection {
    private final Supplier<List<ResourceLocation>> choices;
    private final Predicate<ResourceLocation> unavailable;
    private final @Nullable Consumer<ResourceLocation> picked;
    private final @Nullable ResourceScopeSelectionDraft scope;
    private final Function<ResourceLocation, String> displayName;
    private final ClientSearchState search = new ClientSearchState();
    private List<ResourceLocation> results = List.of();
    private int scroll;
    private long matcherRevision = -1;
    private @Nullable ResourceLocation chosen;

    @Nullable
    ResourceLocation chosen() {
        return chosen;
    }

    private ResourceTypeSelection(
            Supplier<List<ResourceLocation>> choices,
            Predicate<ResourceLocation> unavailable,
            @Nullable ResourceScopeSelectionDraft scope,
            @Nullable Consumer<ResourceLocation> picked,
            Function<ResourceLocation, String> displayName) {
        this.choices = Objects.requireNonNull(choices, "choices");
        this.unavailable = Objects.requireNonNull(unavailable, "unavailable");
        this.scope = scope;
        this.picked = picked;
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        refresh();
    }

    static ResourceTypeSelection scope(
            NodeResourcePolicyDraft draft,
            ResourceScopeSelectionDraft scope,
            Function<ResourceLocation, String> displayName) {
        scope.requireOwner(draft);
        return scope(scope, id -> draft.catalog.find(id) == null, displayName);
    }

    static ResourceTypeSelection scope(
            ResourceScopeSelectionDraft scope,
            Predicate<ResourceLocation> unavailable,
            Function<ResourceLocation, String> displayName) {
        return new ResourceTypeSelection(scope::choices, unavailable, scope, null, displayName);
    }

    static ResourceTypeSelection overrides(
            NodeResourcePolicyDraft draft, Function<ResourceLocation, String> displayName) {
        return overrides(
                () -> {
                    var ids = new ArrayList<ResourceLocation>();
                    for (var descriptor : draft.catalog.entries()) {
                        var id = descriptor.typeId();
                        if (draft.includes(id) && !draft.hasSetting(id)) ids.add(id);
                    }
                    return List.copyOf(ids);
                },
                id -> draft.catalog.find(id) == null,
                draft::addType,
                displayName);
    }

    static ResourceTypeSelection overrides(
            Supplier<List<ResourceLocation>> choices,
            Predicate<ResourceLocation> unavailable,
            @Nullable Consumer<ResourceLocation> picked,
            Function<ResourceLocation, String> displayName) {
        return new ResourceTypeSelection(choices, unavailable, null, picked, displayName);
    }

    ClientSearchState search() {
        return search;
    }

    @Nullable
    ResourceScopeSelectionDraft scope() {
        return scope;
    }

    List<ResourceLocation> results() {
        return results;
    }

    int scroll() {
        return scroll;
    }

    boolean unavailable(ResourceLocation id) {
        return unavailable.test(id);
    }

    void editSearch(String value, long nowTick) {
        search.edit(value, nowTick);
    }

    boolean tick(long nowTick) {
        if (!search.due(nowTick) && matcherRevision == ClientTextSearch.matcherRevision()) return false;
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
        if (!choices.get().contains(id)) throw new IllegalArgumentException("Type is not a current candidate");
        chosen = id;
        if (scope != null) scope.toggle(id);
        else {
            if (picked != null) picked.accept(id);
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
        results = ClientTextSearch.filter(
                (text, matcher, revision) -> {
                    var matches = new ArrayList<ResourceLocation>();
                    for (ResourceLocation id : choices.get()) if (matches(id, text, matcher)) matches.add(id);
                    return List.copyOf(matches);
                },
                query);
        matcherRevision = ClientTextSearch.matcherRevision();
        scroll = 0;
    }

    private boolean matches(ResourceLocation id, String query, java.util.function.BiPredicate<String, String> matcher) {
        return query.isEmpty()
                || id.toString().contains(query)
                || matcher.test(ClientTextSearch.fold(displayName.apply(id)), query);
    }
}
