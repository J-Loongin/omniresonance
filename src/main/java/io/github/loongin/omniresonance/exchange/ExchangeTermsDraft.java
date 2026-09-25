// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable untrusted edit intent, never approval evidence. No component bytes, preset body or actor identity is
 * accepted here. Construction is pure, detaches rate values and rejects invalid bounds; the server must resolve
 * ownership, retained snapshot identity, library revisions and registered types before authoritative use.
 */
public record ExchangeTermsDraft(
        ResourceScope scope,
        FilterMode filterMode,
        FilterChoice filter,
        long defaultRate,
        Map<ResourceLocation, Long> rates,
        int intervalTicks) {
    public sealed interface FilterChoice permits None, KeepApproved, OwnerPreset {}

    public record None() implements FilterChoice {}

    public record KeepApproved() implements FilterChoice {}

    public record OwnerPreset(UUID presetId, long libraryRevision) implements FilterChoice {
        public OwnerPreset {
            Objects.requireNonNull(presetId);
            if (libraryRevision < 0) throw new IllegalArgumentException("Invalid preset library revision");
        }
    }

    public ExchangeTermsDraft {
        Objects.requireNonNull(filter);
        ExchangeTerms validated = new ExchangeTerms(scope, filterMode, null, defaultRate, rates, intervalTicks);
        rates = validated.rates();
    }
}
