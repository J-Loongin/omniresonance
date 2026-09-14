// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.TransferDirection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable thread-safe multi-resource policy that owns detached override maps. Construction, lookup, migration, and
 * direction switching perform no world access, simulation, persistence, or authority mutation; invalid pure values
 * are rejected before a policy exists.
 */
public sealed interface ResourceTransferPolicy permits ResourceTransferPolicy.Input, ResourceTransferPolicy.Output {
    int DEFAULT_RATE = Integer.MAX_VALUE;

    int intervalTicks();

    ResourceScope scope();

    RedstoneCondition redstoneCondition();

    @Nullable
    UUID filterPresetId();

    FilterMode filterMode();

    Map<ResourceLocation, ? extends TypeOverride> resourcePolicyOverrides();

    default TransferDirection direction() {
        return this instanceof Input ? TransferDirection.INPUT : TransferDirection.OUTPUT;
    }

    default int rate(ResourceLocation resourceTypeId) {
        Objects.requireNonNull(resourceTypeId, "resourceTypeId");
        TypeOverride override = resourcePolicyOverrides().get(resourceTypeId);
        return override == null ? DEFAULT_RATE : override.rate();
    }

    /**
     * Returns an immutable policy that preserves common fields and rates while resetting exclusive fields. The pure
     * operation retains no mutable caller state, performs no simulation or authority mutation, and rejects null.
     */
    default ResourceTransferPolicy switchDirection(TransferDirection direction) {
        Objects.requireNonNull(direction, "direction");
        if (direction == direction()) return this;
        if (direction == TransferDirection.OUTPUT) {
            Map<ResourceLocation, OutputOverride> outputOverrides = new HashMap<>();
            resourcePolicyOverrides()
                    .forEach((id, override) -> outputOverrides.put(id, new OutputOverride(override.rate())));
            return new Output(
                    intervalTicks(), scope(), redstoneCondition(), filterPresetId(), filterMode(), outputOverrides, 0);
        }
        Map<ResourceLocation, InputOverride> inputOverrides = new HashMap<>();
        resourcePolicyOverrides()
                .forEach((id, override) -> inputOverrides.put(
                        id,
                        new InputOverride(override.rate(), BatchMode.GREEDY, ResourceTypes.defaultExactBatchSize(id))));
        return new Input(
                intervalTicks(), scope(), redstoneCondition(), filterPresetId(), filterMode(), inputOverrides, 0);
    }

    /**
     * Creates a new ALL-scoped immutable policy. This pure factory owns its values, performs no simulation or
     * authority mutation, and rejects a null direction.
     */
    static ResourceTransferPolicy defaults(TransferDirection direction) {
        Objects.requireNonNull(direction, "direction");
        return direction == TransferDirection.INPUT
                ? new Input(1, ResourceScope.all(), RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, Map.of(), 0)
                : new Output(1, ResourceScope.all(), RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, Map.of(), 0);
    }

    /**
     * Converts every M2 field into an immutable item-only policy without mutating the source or authority. This pure,
     * thread-safe conversion performs no simulation and rejects null or invalid source values.
     */
    static ResourceTransferPolicy legacy(ItemTransferPolicy legacy) {
        Objects.requireNonNull(legacy, "legacy");
        ResourceScope itemOnly = ResourceScope.customSet(java.util.Set.of(ResourceTypes.ITEM));
        if (legacy instanceof ItemTransferPolicy.Input input) {
            return new Input(
                    input.intervalTicks(),
                    itemOnly,
                    input.redstoneCondition(),
                    input.filterPresetId(),
                    input.filterMode(),
                    Map.of(
                            ResourceTypes.ITEM,
                            new InputOverride(
                                    input.rate(),
                                    BatchMode.GREEDY,
                                    ResourceTypes.defaultExactBatchSize(ResourceTypes.ITEM))),
                    input.keepCount());
        }
        ItemTransferPolicy.Output output = (ItemTransferPolicy.Output) legacy;
        return new Output(
                output.intervalTicks(),
                itemOnly,
                output.redstoneCondition(),
                output.filterPresetId(),
                output.filterMode(),
                Map.of(ResourceTypes.ITEM, new OutputOverride(output.rate())),
                output.priority());
    }

    private static void validateCommon(
            int intervalTicks,
            ResourceScope scope,
            RedstoneCondition redstoneCondition,
            FilterMode filterMode,
            Map<ResourceLocation, ? extends TypeOverride> overrides) {
        if (intervalTicks < 1) throw new IllegalArgumentException("Interval must be positive");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(redstoneCondition, "redstoneCondition");
        Objects.requireNonNull(filterMode, "filterMode");
        Objects.requireNonNull(overrides, "resourcePolicyOverrides");
        if (overrides.size() > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS) {
            throw new IllegalArgumentException("Too many resource policy overrides");
        }
        overrides.forEach((id, override) -> {
            ResourceScope.validateResourceTypeId(id);
            Objects.requireNonNull(override, "resourcePolicyOverride");
            if (scope.kind() == ResourceScope.Kind.CUSTOM_SET && !scope.includes(id)) {
                throw new IllegalArgumentException("Resource policy override is outside the custom scope");
            }
        });
    }

    private static Map<ResourceLocation, InputOverride> normalizeInputOverrides(
            Map<ResourceLocation, InputOverride> overrides) {
        HashMap<ResourceLocation, InputOverride> normalized = new HashMap<>();
        overrides.forEach((id, override) -> {
            InputOverride canonical = override.batchMode() == BatchMode.GREEDY
                    ? new InputOverride(override.rate(), BatchMode.GREEDY, ResourceTypes.defaultExactBatchSize(id))
                    : override;
            if (!canonical.isDefault()) normalized.put(id, canonical);
        });
        return Map.copyOf(normalized);
    }

    private static Map<ResourceLocation, OutputOverride> normalizeOutputOverrides(
            Map<ResourceLocation, OutputOverride> overrides) {
        HashMap<ResourceLocation, OutputOverride> normalized = new HashMap<>();
        overrides.forEach((id, override) -> {
            if (!override.isDefault()) normalized.put(id, override);
        });
        return Map.copyOf(normalized);
    }

    /** Stable pure input batch discriminator; values perform no simulation or mutation. */
    enum BatchMode {
        GREEDY,
        EXACT
    }

    /** Immutable per-type rate view shared by direction-specific overrides. */
    sealed interface TypeOverride permits InputOverride, OutputOverride {
        int rate();
    }

    /** Pure immutable input override; construction rejects non-positive rates or batches and null modes. */
    record InputOverride(int rate, BatchMode batchMode, long batchSize) implements TypeOverride {
        public InputOverride {
            if (rate < 1) throw new IllegalArgumentException("Rate must be positive");
            Objects.requireNonNull(batchMode, "batchMode");
            if (batchSize < 1) throw new IllegalArgumentException("Batch size must be positive");
        }

        boolean isDefault() {
            return rate == DEFAULT_RATE && batchMode == BatchMode.GREEDY;
        }
    }

    /** Pure immutable output override; construction rejects non-positive rates. */
    record OutputOverride(int rate) implements TypeOverride {
        public OutputOverride {
            if (rate < 1) throw new IllegalArgumentException("Rate must be positive");
        }

        boolean isDefault() {
            return rate == DEFAULT_RATE;
        }
    }

    /**
     * Immutable input policy owning a detached normalized override map. Construction performs no simulation or
     * mutation and rejects invalid common fields, out-of-scope overrides, or a negative keep count.
     */
    record Input(
            int intervalTicks,
            ResourceScope scope,
            RedstoneCondition redstoneCondition,
            @Nullable UUID filterPresetId,
            FilterMode filterMode,
            Map<ResourceLocation, InputOverride> resourcePolicyOverrides,
            long keepCount)
            implements ResourceTransferPolicy {
        public Input {
            validateCommon(intervalTicks, scope, redstoneCondition, filterMode, resourcePolicyOverrides);
            if (keepCount < 0) throw new IllegalArgumentException("Keep count must be non-negative");
            resourcePolicyOverrides = normalizeInputOverrides(resourcePolicyOverrides);
        }
    }

    /**
     * Immutable output policy owning a detached normalized override map. Construction performs no simulation or
     * mutation and rejects invalid common fields or out-of-scope overrides.
     */
    record Output(
            int intervalTicks,
            ResourceScope scope,
            RedstoneCondition redstoneCondition,
            @Nullable UUID filterPresetId,
            FilterMode filterMode,
            Map<ResourceLocation, OutputOverride> resourcePolicyOverrides,
            int priority)
            implements ResourceTransferPolicy {
        public Output {
            validateCommon(intervalTicks, scope, redstoneCondition, filterMode, resourcePolicyOverrides);
            resourcePolicyOverrides = normalizeOutputOverrides(resourcePolicyOverrides);
        }
    }
}
