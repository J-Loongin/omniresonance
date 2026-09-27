// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.RedstoneCondition;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Client-owned detached typed text draft. Invalid numeric text remains editable. Missing rows carry only IDs;
 * authority reconciliation supplies their original values. Ordering is cached until structural edits, never sorted
 * or rebuilt during scrolling. The current page owns this draft and discards it when editing ends.
 */
final class NodeResourcePolicyDraft {
    final ResourcePolicyEdit original;
    final NodeResourceTypeCatalog.Snapshot catalog;
    final boolean domain;
    TransferDirection direction;
    String interval;
    String quantity;
    RedstoneCondition redstone;
    FilterMode filterMode;

    @Nullable
    UUID presetId;

    @Nullable
    String presetName;

    private ResourcePolicyEdit.Scope scope;
    private Set<ResourceLocation> scopeIds;
    private final ResourceScope.Kind originalScopeKind;
    private final Set<ResourceLocation> originalScopeIds;
    private final Map<ResourceLocation, TypeDraft> rows = new LinkedHashMap<>();
    private final LinkedHashSet<ResourceLocation> retainedMissing = new LinkedHashSet<>();
    private final Set<ResourceLocation> originalMissing;
    private List<ResourceLocation> settingIds;
    private boolean discardPreviousDirectionFields;
    private long structureRevision;

    NodeResourcePolicyDraft(
            ResourcePolicyEdit original, @Nullable String presetName, NodeResourceTypeCatalog.Snapshot catalog) {
        this(original, presetName, catalog, false);
    }

    NodeResourcePolicyDraft(
            ResourcePolicyEdit original,
            @Nullable String presetName,
            NodeResourceTypeCatalog.Snapshot catalog,
            boolean domain) {
        this.domain = domain;
        this.original = Objects.requireNonNull(original, "original");
        originalMissing = Set.copyOf(original.retainedMissingIds());
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        direction = original.fields() instanceof ResourcePolicyEdit.InputFields
                ? TransferDirection.INPUT
                : TransferDirection.OUTPUT;
        interval = Integer.toString(original.intervalTicks());
        quantity = original.fields() instanceof ResourcePolicyEdit.InputFields input
                ? Long.toString(input.keepCount())
                : Integer.toString(((ResourcePolicyEdit.OutputFields) original.fields()).priority());
        redstone = original.redstoneCondition();
        filterMode = original.filterMode();
        presetId = original.filterPresetId();
        this.presetName = presetName;
        scope = original.scope();
        scopeIds = Set.copyOf(scope.ids());
        originalScopeKind = scope.kind();
        originalScopeIds = scopeIds;
        discardPreviousDirectionFields = original.discardPreviousDirectionFields();
        for (var row : original.rows()) {
            if (catalog.find(row.typeId()) == null) throw new IllegalArgumentException("Unregistered editable row");
            rows.put(row.typeId(), new TypeDraft(row.value()));
        }
        for (ResourceLocation id : original.retainedMissingIds()) {
            if (catalog.find(id) != null) throw new IllegalArgumentException("Registered row marked unavailable");
            retainedMissing.add(id);
        }
        rebuildOrder();
    }

    ResourcePolicyEdit edit() {
        var values = new ArrayList<ResourcePolicyEdit.Row>(rows.size());
        for (var entry : rows.entrySet())
            values.add(
                    new ResourcePolicyEdit.Row(entry.getKey(), entry.getValue().value(direction)));
        return new ResourcePolicyEdit(
                Integer.parseInt(interval.trim()),
                scope,
                redstone,
                presetId,
                filterMode,
                direction == TransferDirection.INPUT
                        ? new ResourcePolicyEdit.InputFields(Long.parseLong(quantity.trim()))
                        : new ResourcePolicyEdit.OutputFields(Integer.parseInt(quantity.trim())),
                values,
                List.copyOf(retainedMissing),
                discardPreviousDirectionFields);
    }

    boolean dirty() {
        try {
            if (discardPreviousDirectionFields != original.discardPreviousDirectionFields()
                    || Integer.parseInt(interval.trim()) != original.intervalTicks()
                    || redstone != original.redstoneCondition()
                    || filterMode != original.filterMode()
                    || !Objects.equals(presetId, original.filterPresetId())
                    || scope.kind() != originalScopeKind
                    || !scopeIds.equals(originalScopeIds)
                    || rows.size() != original.rows().size()
                    || !retainedMissing.equals(originalMissing)) return true;
            if (original.fields() instanceof ResourcePolicyEdit.InputFields input) {
                if (direction != TransferDirection.INPUT || Long.parseLong(quantity.trim()) != input.keepCount())
                    return true;
            } else if (direction != TransferDirection.OUTPUT
                    || Integer.parseInt(quantity.trim())
                            != ((ResourcePolicyEdit.OutputFields) original.fields()).priority()) return true;
            for (var originalRow : original.rows()) {
                TypeDraft row = rows.get(originalRow.typeId());
                if (row == null
                        || Long.parseLong(row.rate.trim())
                                != originalRow.value().rate()) return true;
                if (originalRow.value() instanceof ResourceTransferPolicy.InputOverride input
                        && (row.batchMode != input.batchMode()
                                || Long.parseLong(row.batch.trim()) != input.batchSize())) return true;
            }
            return false;
        } catch (IllegalArgumentException invalidText) {
            return true;
        }
    }

    boolean dirtyIncluding(@Nullable NodeResourceScopeDraft overlay) {
        return dirty() || overlay != null && overlay.dirty();
    }

    void confirmDirectionChange() {
        direction = direction == TransferDirection.INPUT ? TransferDirection.OUTPUT : TransferDirection.INPUT;
        quantity = "0";
        discardPreviousDirectionFields = true;
        for (var entry : rows.entrySet()) {
            var type = entry.getValue();
            type.batchMode = ResourceTransferPolicy.BatchMode.GREEDY;
            type.batch = direction == TransferDirection.INPUT
                    ? Long.toString(catalog.find(entry.getKey()).defaultBatchSize())
                    : "";
        }
    }

    ResourcePolicyEdit.Scope scope() {
        return scope;
    }

    boolean includes(ResourceLocation id) {
        return scope.kind() == ResourceScope.Kind.ALL || scopeIds.contains(id);
    }

    boolean unavailable(ResourceLocation id) {
        return retainedMissing.contains(id);
    }

    boolean hasSetting(ResourceLocation id) {
        return rows.containsKey(id) || retainedMissing.contains(id);
    }

    List<ResourceLocation> settingIds() {
        return settingIds;
    }

    int knownSettingCount() {
        return rows.size();
    }

    long structureRevision() {
        return structureRevision;
    }

    TypeDraft type(ResourceLocation id) {
        TypeDraft row = rows.get(id);
        if (row == null) throw new IllegalArgumentException("No editable type row");
        return row;
    }

    void addType(ResourceLocation id) {
        var descriptor = catalog.find(id);
        if (descriptor == null
                || !includes(id)
                || rows.containsKey(id)
                || settingIds.size() >= ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS)
            throw new IllegalArgumentException("Type cannot be added");
        rows.put(
                id,
                new TypeDraft(
                        direction == TransferDirection.INPUT
                                ? new ResourceTransferPolicy.InputOverride(
                                        Integer.MAX_VALUE,
                                        ResourceTransferPolicy.BatchMode.GREEDY,
                                        descriptor.defaultBatchSize())
                                : new ResourceTransferPolicy.OutputOverride(Integer.MAX_VALUE)));
        rebuildOrder();
    }

    void restoreDefault(ResourceLocation id) {
        boolean changed = rows.remove(id) != null;
        changed |= retainedMissing.remove(id);
        if (changed) rebuildOrder();
    }

    NodeResourceScopeDraft openScope() {
        return new NodeResourceScopeDraft(this, scope, catalog, retainedMissing);
    }

    int removedOverrideCount(NodeResourceScopeDraft selection) {
        selection.requireOwner(this);
        selection.selection();
        int count = 0;
        for (ResourceLocation id : settingIds) if (!selection.includes(id)) count++;
        return count;
    }

    void applyScope(NodeResourceScopeDraft selection, boolean confirmedRemoval) {
        selection.requireOwner(this);
        ResourcePolicyEdit.Scope next = selection.selection();
        if (removedOverrideCount(selection) > 0 && !confirmedRemoval)
            throw new IllegalStateException("Scope narrowing requires confirmation");
        rows.keySet().removeIf(id -> !selection.includes(id));
        retainedMissing.removeIf(id -> !selection.includes(id));
        scope = next;
        scopeIds = Set.copyOf(next.ids());
        rebuildOrder();
    }

    private void rebuildOrder() {
        var ids = new ArrayList<ResourceLocation>(rows.size() + retainedMissing.size());
        ids.addAll(rows.keySet());
        ids.addAll(retainedMissing);
        settingIds = List.copyOf(ids);
        structureRevision++;
    }

    static final class TypeDraft {
        String rate;
        ResourceTransferPolicy.BatchMode batchMode;
        String batch;

        private TypeDraft(ResourceTransferPolicy.TypeOverride value) {
            rate = Long.toString(value.rate());
            batchMode = value instanceof ResourceTransferPolicy.InputOverride input
                    ? input.batchMode()
                    : ResourceTransferPolicy.BatchMode.GREEDY;
            batch = value instanceof ResourceTransferPolicy.InputOverride input ? Long.toString(input.batchSize()) : "";
        }

        private ResourceTransferPolicy.TypeOverride value(TransferDirection direction) {
            long parsedRate = Long.parseLong(rate.trim());
            return direction == TransferDirection.INPUT
                    ? new ResourceTransferPolicy.InputOverride(parsedRate, batchMode, Long.parseLong(batch.trim()))
                    : new ResourceTransferPolicy.OutputOverride(parsedRate);
        }
    }
}
