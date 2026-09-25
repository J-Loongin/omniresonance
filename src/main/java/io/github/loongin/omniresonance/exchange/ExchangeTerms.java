// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable exchange terms, safe on any thread. Construction detaches sparse rate overrides, performs no world
 * access, simulation or mutation, and rejects invalid bounds. No adapter discovery or gameplay defaults occur;
 * the service must validate admission and bind these terms to the approved revision before execution.
 */
public record ExchangeTerms(
        ResourceScope scope,
        FilterMode filterMode,
        @Nullable ExchangeFilterSnapshot filter,
        long defaultRate,
        Map<ResourceLocation, Long> rates,
        int intervalTicks) {
    public ExchangeTerms {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(filterMode);
        Objects.requireNonNull(rates);
        if (defaultRate < 1 || intervalTicks < 1 || rates.size() > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS)
            throw new IllegalArgumentException("Invalid exchange rate, interval or override count");
        for (var entry : rates.entrySet()) {
            ResourceLocation type = Objects.requireNonNull(entry.getKey());
            if (type.toString().length() > ResourceScope.MAXIMUM_RESOURCE_TYPE_ID_BYTES
                    || Objects.requireNonNull(entry.getValue()) < 1)
                throw new IllegalArgumentException("Invalid exchange resource override");
        }
        rates = Map.copyOf(rates);
    }

    /** Pure immutable lookup; unlisted types use the explicit default, independently of scope eligibility. */
    public long rate(ResourceLocation type) {
        return rates.getOrDefault(Objects.requireNonNull(type), defaultRate);
    }
}
