// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.network.TransferDirection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable, thread-safe persisted policy envelope owning a detached map of existing missing-adapter overrides.
 * Pure construction validates local values without world access, simulation, or authority mutation. Full serialized
 * object size is validated by the persistence codec before publication. This value does not authorize client IDs.
 */
public record StoredResourcePolicy(
        ResourceTransferPolicy effectivePolicy, Map<ResourceLocation, RawOverride> missingTypeOverrides) {
    public StoredResourcePolicy {
        Objects.requireNonNull(effectivePolicy, "effectivePolicy");
        Objects.requireNonNull(missingTypeOverrides, "missingTypeOverrides");
        if ((long) effectivePolicy.resourcePolicyOverrides().size() + missingTypeOverrides.size()
                > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS) {
            throw new IllegalArgumentException("Too many resource policy overrides");
        }
        missingTypeOverrides.forEach((id, raw) -> {
            ResourceScope.validateResourceTypeId(id);
            Objects.requireNonNull(raw, "rawOverride");
            if (!effectivePolicy.scope().includes(id)
                    || effectivePolicy.resourcePolicyOverrides().containsKey(id)) {
                throw new IllegalArgumentException("Overlapping or out-of-scope missing override");
            }
            if (effectivePolicy.direction() == TransferDirection.OUTPUT && raw.batchMode() != null) {
                throw new IllegalArgumentException("Input fields in output override");
            }
        });
        missingTypeOverrides = Map.copyOf(missingTypeOverrides);
    }

    /**
     * Pure thread-safe direction conversion preserving common fields and raw rate presence, clearing exclusive
     * fields. Returns immutable detached state without simulation or authority mutation; rejects null directions.
     */
    public StoredResourcePolicy switchDirection(TransferDirection direction) {
        Objects.requireNonNull(direction, "direction");
        if (effectivePolicy.direction() == direction) return this;
        Map<ResourceLocation, RawOverride> missing = new HashMap<>();
        missingTypeOverrides.forEach((id, raw) -> missing.put(id, new RawOverride(raw.rate(), null, null)));
        return new StoredResourcePolicy(effectivePolicy.switchDirection(direction), missing);
    }

    /**
     * Immutable field-preserving generic override from trusted existing persistence. Null means absent, including
     * explicit defaults when present. Pure construction rejects invalid rates, batches, and mode/size combinations;
     * it performs no simulation or mutation and retains no mutable state.
     */
    public record RawOverride(
            @Nullable Long rate,
            @Nullable ResourceTransferPolicy.BatchMode batchMode,
            @Nullable Long batchSize) {
        public RawOverride(int rate, @Nullable ResourceTransferPolicy.BatchMode batchMode, @Nullable Long batchSize) {
            this(Long.valueOf(rate), batchMode, batchSize);
        }

        public RawOverride {
            if (rate != null && rate < 1) throw new IllegalArgumentException("Rate must be positive");
            if (batchSize != null && batchSize < 1) throw new IllegalArgumentException("Batch must be positive");
            if (batchMode == null && batchSize != null
                    || batchMode == ResourceTransferPolicy.BatchMode.EXACT && batchSize == null) {
                throw new IllegalArgumentException("Invalid batch mode and size combination");
            }
        }
    }
}
