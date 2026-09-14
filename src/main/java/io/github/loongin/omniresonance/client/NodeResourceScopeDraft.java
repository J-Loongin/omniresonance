// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** One scope overlay's detached selection; cancel means discard this object. No parent mutation before apply. */
final class NodeResourceScopeDraft {
    private final NodeResourcePolicyDraft owner;
    private final ResourcePolicyEdit.Scope original;
    private final Set<ResourceLocation> allowed;
    private final List<ResourceLocation> choices;
    private final Set<ResourceLocation> originalIds;
    private final LinkedHashSet<ResourceLocation> selected = new LinkedHashSet<>();
    private ResourceScope.Kind kind;

    NodeResourceScopeDraft(
            NodeResourcePolicyDraft owner,
            ResourcePolicyEdit.Scope original,
            NodeResourceTypeCatalog.Snapshot catalog,
            Set<ResourceLocation> missingRows) {
        this.owner = owner;
        this.original = original;
        originalIds = Set.copyOf(original.ids());
        kind = original.kind();
        var ids = new LinkedHashSet<ResourceLocation>();
        for (var descriptor : catalog.entries()) {
            ids.add(descriptor.typeId());
            if (kind == ResourceScope.Kind.ALL) selected.add(descriptor.typeId());
        }
        ids.addAll(original.ids());
        ids.addAll(missingRows);
        choices = List.copyOf(ids);
        allowed = Set.copyOf(ids);
        if (kind == ResourceScope.Kind.CUSTOM_SET) selected.addAll(original.ids());
    }

    void requireOwner(NodeResourcePolicyDraft draft) {
        if (owner != draft) throw new IllegalArgumentException("Scope draft belongs to another editor");
    }

    List<ResourceLocation> choices() {
        return choices;
    }

    boolean selected(ResourceLocation id) {
        return selected.contains(id);
    }

    int selectedCount() {
        return selected.size();
    }

    ResourceScope.Kind kind() {
        return kind;
    }

    void all() {
        kind = ResourceScope.Kind.ALL;
    }

    void custom() {
        kind = ResourceScope.Kind.CUSTOM_SET;
    }

    void toggle(ResourceLocation id) {
        if (!allowed.contains(id)) throw new IllegalArgumentException("Unavailable scope choice");
        kind = ResourceScope.Kind.CUSTOM_SET;
        if (!selected.remove(id)) selected.add(id);
    }

    boolean includes(ResourceLocation id) {
        return kind == ResourceScope.Kind.ALL || selected.contains(id);
    }

    ResourcePolicyEdit.Scope selection() {
        return kind == ResourceScope.Kind.ALL
                ? ResourcePolicyEdit.Scope.all()
                : ResourcePolicyEdit.Scope.custom(List.copyOf(selected));
    }

    boolean dirty() {
        if (kind != original.kind()) return true;
        return kind == ResourceScope.Kind.CUSTOM_SET && !selected.equals(originalIds);
    }
}
