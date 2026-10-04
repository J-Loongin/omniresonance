// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Exchange data adapters only; selection, search, layout and widget behavior belong to the shared node-derived UI. */
final class ExchangeResourceSelection {
    private ExchangeResourceSelection() {}

    static ResourceTypeSelection scope(
            Object owner,
            List<ResourceLocation> available,
            boolean all,
            Set<ResourceLocation> selected,
            Set<ResourceLocation> missing) {
        var supported = Set.copyOf(available);
        var original = all ? ResourcePolicyEdit.Scope.all() : ResourcePolicyEdit.Scope.custom(List.copyOf(selected));
        var draft = new ResourceScopeSelectionDraft(owner, original, available, missing);
        return ResourceTypeSelection.scope(draft, id -> !supported.contains(id), ExchangeResourceSelection::name);
    }

    static ResourceTypeSelection types(
            List<ResourceLocation> available,
            Set<ResourceLocation> configured,
            boolean all,
            Set<ResourceLocation> scope) {
        var supported = Set.copyOf(available);
        return ResourceTypeSelection.overrides(
                () -> {
                    var candidates = new ArrayList<ResourceLocation>();
                    for (var id : available)
                        if (!configured.contains(id) && (all || scope.contains(id))) candidates.add(id);
                    return List.copyOf(candidates);
                },
                id -> !supported.contains(id),
                null,
                ExchangeResourceSelection::name);
    }

    private static String name(ResourceLocation id) {
        return NodeResourcePolicyView.typeName(id).getString();
    }
}
