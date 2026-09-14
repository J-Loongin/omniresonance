// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.persistence.ResourcePolicyNbt;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable untrusted edit intent owning ordered, non-normalized rows and ID lists. Pure caller-thread
 * construction performs no simulation, world access or mutation. Invalid shape fails before publication.
 * The UI sets discardPreviousDirectionFields after any confirmed direction switch, and never clears it
 * until editing ends, including when switching back. Missing values are never supplied by clients.
 */
public record ResourcePolicyEdit(
        int intervalTicks,
        Scope scope,
        RedstoneCondition redstoneCondition,
        @Nullable UUID filterPresetId,
        FilterMode filterMode,
        DirectionFields fields,
        List<Row> rows,
        List<ResourceLocation> retainedMissingIds,
        boolean discardPreviousDirectionFields) {
    public ResourcePolicyEdit {
        if (intervalTicks < 1) throw new IllegalArgumentException("Interval must be positive");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(redstoneCondition, "redstoneCondition");
        Objects.requireNonNull(filterMode, "filterMode");
        Objects.requireNonNull(fields, "fields");
        if (rows.size() > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS
                || (long) rows.size() + retainedMissingIds.size() > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS)
            throw new IllegalArgumentException("Too many edit rows");
        Set<ResourceLocation> seen = new HashSet<>();
        for (Row row : rows) {
            if (!seen.add(row.typeId())) throw new IllegalArgumentException("Duplicate edit row");
            if ((fields instanceof InputFields) != (row.value() instanceof ResourceTransferPolicy.InputOverride))
                throw new IllegalArgumentException("Mixed direction fields");
        }
        validateIds(retainedMissingIds);
        for (ResourceLocation id : retainedMissingIds)
            if (!seen.add(id)) throw new IllegalArgumentException("Overlapping retained ID");
        rows = List.copyOf(rows);
        retainedMissingIds = List.copyOf(retainedMissingIds);
    }

    /**
     * Pure trusted-state projection for an edit seed, with stable ID ordering and discard flag false.
     * Returns detached immutable values and missing IDs only, never raw missing values. It performs no
     * simulation or authority mutation. The server must refresh missing/registered classification before
     * supplying state. The compact edit codec guarantees any valid stored-policy seed fits its wire limit;
     * its count-only admission still runs before allocating transport bytes.
     */
    public static ResourcePolicyEdit fromStored(StoredResourcePolicy stored) {
        Objects.requireNonNull(stored, "stored");
        ResourceTransferPolicy policy = stored.effectivePolicy();
        List<Row> rows = policy.resourcePolicyOverrides().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Row(entry.getKey(), entry.getValue()))
                .toList();
        Scope scope = policy.scope().kind() == ResourceScope.Kind.ALL
                ? Scope.all()
                : Scope.custom(
                        policy.scope().resourceTypeIds().stream().sorted().toList());
        DirectionFields fields = policy instanceof ResourceTransferPolicy.Input input
                ? new InputFields(input.keepCount())
                : new OutputFields(((ResourceTransferPolicy.Output) policy).priority());
        return new ResourcePolicyEdit(
                policy.intervalTicks(),
                scope,
                policy.redstoneCondition(),
                policy.filterPresetId(),
                policy.filterMode(),
                fields,
                rows,
                stored.missingTypeOverrides().keySet().stream().sorted().toList(),
                false);
    }

    private static void validateIds(List<ResourceLocation> ids) {
        if (ids.size() > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS)
            throw new IllegalArgumentException("Too many type IDs");
        Set<ResourceLocation> seen = new HashSet<>();
        for (ResourceLocation id : ids) {
            ResourceScope.validateResourceTypeId(id);
            if (!seen.add(id)) throw new IllegalArgumentException("Duplicate type ID");
        }
    }

    /** Exclusive immutable direction values; these own no external state and have no side effects. */
    public sealed interface DirectionFields permits InputFields, OutputFields {}
    /** Pure input fields; negative keep counts fail at construction. */
    public record InputFields(long keepCount) implements DirectionFields {
        public InputFields {
            if (keepCount < 0) throw new IllegalArgumentException("Negative keep count");
        }
    }
    /** Pure output fields; every signed integer priority is legal. */
    public record OutputFields(int priority) implements DirectionFields {}
    /** Unnormalized typed row, detached from all mutable client state. */
    public record Row(ResourceLocation typeId, ResourceTransferPolicy.TypeOverride value) {
        public Row {
            ResourceScope.validateResourceTypeId(typeId);
            Objects.requireNonNull(value, "value");
        }
    }
    /** Ordered selection intent; duplicates must not disappear into a set before validation. */
    public record Scope(ResourceScope.Kind kind, List<ResourceLocation> ids) {
        public Scope {
            Objects.requireNonNull(kind, "kind");
            validateIds(ids);
            if ((kind == ResourceScope.Kind.ALL) != ids.isEmpty())
                throw new IllegalArgumentException("ALL has no snapshot; CUSTOM must be nonempty");
            ids = List.copyOf(ids);
        }
        /** Returns an immutable snapshot-free ALL intent without discovery or mutation. */
        public static Scope all() {
            return new Scope(ResourceScope.Kind.ALL, List.of());
        }
        /** Returns a detached custom intent; invalid or duplicate IDs throw without mutation. */
        public static Scope custom(List<ResourceLocation> ids) {
            return new Scope(ResourceScope.Kind.CUSTOM_SET, ids);
        }
    }
    /**
     * Reconciles synchronously against immutable trusted current state and a caller-frozen registered set.
     * Callers must first validate lease, revision, identity, permissions and object relations on the server
     * thread. This pure method neither performs those checks nor world access, simulation or mutation.
     * It owns the detached result and returns only after actual persistence encoding fits 16 MiB;
     * any invalid intent throws with no partial result. New configurations supply trusted defaults with no raw rows.
     */
    public StoredResourcePolicy reconcile(StoredResourcePolicy current, Set<ResourceLocation> registered) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(registered, "registered");
        ResourceScope requested =
                scope.kind() == ResourceScope.Kind.ALL ? ResourceScope.all() : ResourceScope.customSet(scope.ids());
        ResourceScope oldScope = current.effectivePolicy().scope();
        for (ResourceLocation id : scope.ids()) {
            if (!registered.contains(id)
                    && !(oldScope.kind() == ResourceScope.Kind.CUSTOM_SET && oldScope.includes(id))
                    && !current.missingTypeOverrides().containsKey(id))
                throw new IllegalArgumentException("Invented unknown scope ID");
        }
        Map<ResourceLocation, ResourceTransferPolicy.InputOverride> input = new HashMap<>();
        Map<ResourceLocation, ResourceTransferPolicy.OutputOverride> output = new HashMap<>();
        for (Row row : rows) {
            if (!registered.contains(row.typeId()) || !requested.includes(row.typeId()))
                throw new IllegalArgumentException("Unknown or out-of-scope edit row");
            if (row.value() instanceof ResourceTransferPolicy.InputOverride value) input.put(row.typeId(), value);
            else output.put(row.typeId(), (ResourceTransferPolicy.OutputOverride) row.value());
        }
        TransferDirection direction =
                fields instanceof InputFields ? TransferDirection.INPUT : TransferDirection.OUTPUT;
        boolean clear = discardPreviousDirectionFields
                || direction != current.effectivePolicy().direction();
        Map<ResourceLocation, StoredResourcePolicy.RawOverride> missing = new HashMap<>();
        for (ResourceLocation id : retainedMissingIds) {
            StoredResourcePolicy.RawOverride raw =
                    current.missingTypeOverrides().get(id);
            if (raw == null || registered.contains(id) || !requested.includes(id))
                throw new IllegalArgumentException("Invalid retained missing ID");
            missing.put(id, clear ? new StoredResourcePolicy.RawOverride(raw.rate(), null, null) : raw);
        }
        ResourceTransferPolicy policy = fields instanceof InputFields values
                ? new ResourceTransferPolicy.Input(
                        intervalTicks,
                        requested,
                        redstoneCondition,
                        filterPresetId,
                        filterMode,
                        input,
                        values.keepCount())
                : new ResourceTransferPolicy.Output(
                        intervalTicks,
                        requested,
                        redstoneCondition,
                        filterPresetId,
                        filterMode,
                        output,
                        ((OutputFields) fields).priority());
        StoredResourcePolicy result = new StoredResourcePolicy(policy, missing);

        ResourcePolicyNbt.encode(result);
        return result;
    }
}
