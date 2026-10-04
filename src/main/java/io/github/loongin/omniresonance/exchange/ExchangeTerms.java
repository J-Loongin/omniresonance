// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.HashMap;
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
        int intervalTicks,
        Map<ResourceLocation, ResourceTransferPolicy.InputOverride> resourceParameters) {
    public static final int DEFAULT_RATE = ResourceTransferPolicy.DEFAULT_RATE;

    /** Pure legacy constructor; detaches rates and supplies greedy node parameters without changing their quantities. */
    public ExchangeTerms(
            ResourceScope scope,
            FilterMode filterMode,
            @Nullable ExchangeFilterSnapshot filter,
            long defaultRate,
            Map<ResourceLocation, Long> rates,
            int intervalTicks) {
        this(scope, filterMode, filter, defaultRate, rates, intervalTicks, Map.of());
    }

    public ExchangeTerms {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(filterMode);
        Objects.requireNonNull(rates);
        Objects.requireNonNull(resourceParameters);
        if (defaultRate < 1
                || intervalTicks < 1
                || rates.size() > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS
                || resourceParameters.size() > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS)
            throw new IllegalArgumentException("Invalid exchange rate, interval or override count");
        for (var entry : rates.entrySet()) {
            ResourceLocation type = Objects.requireNonNull(entry.getKey());
            if (type.toString().length() > ResourceScope.MAXIMUM_RESOURCE_TYPE_ID_BYTES
                    || Objects.requireNonNull(entry.getValue()) < 1)
                throw new IllegalArgumentException("Invalid exchange resource override");
        }
        var parameters = new HashMap<ResourceLocation, ResourceTransferPolicy.InputOverride>(resourceParameters);
        for (var entry : rates.entrySet()) {
            var parameter = parameters.get(entry.getKey());
            if (parameter == null)
                parameters.put(
                        entry.getKey(),
                        new ResourceTransferPolicy.InputOverride(
                                entry.getValue(),
                                ResourceTransferPolicy.BatchMode.GREEDY,
                                ResourceTypes.defaultExactBatchSize(entry.getKey())));
            else if (parameter.rate() != entry.getValue())
                throw new IllegalArgumentException("Conflicting exchange resource rates");
        }
        if (parameters.size() > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS)
            throw new IllegalArgumentException("Too many exchange resource parameters");
        var rateView = new HashMap<ResourceLocation, Long>();
        for (var entry : parameters.entrySet()) {
            if (Objects.requireNonNull(entry.getKey()).toString().length()
                    > ResourceScope.MAXIMUM_RESOURCE_TYPE_ID_BYTES)
                throw new IllegalArgumentException("Exchange resource ID too long");
            rateView.put(
                    entry.getKey(), Objects.requireNonNull(entry.getValue()).rate());
        }
        resourceParameters = Map.copyOf(parameters);
        rates = Map.copyOf(rateView);
    }

    /** Pure immutable lookup; unlisted types use the explicit default, independently of scope eligibility. */
    public long rate(ResourceLocation type) {
        return rates.getOrDefault(Objects.requireNonNull(type), defaultRate);
    }

    /** Pure immutable parameter lookup; defaults are greedy and do not mutate authority or consume a quota. */
    public ResourceTransferPolicy.InputOverride parameter(ResourceLocation type) {
        Objects.requireNonNull(type);
        var parameter = resourceParameters.get(type);
        return parameter == null
                ? new ResourceTransferPolicy.InputOverride(
                        defaultRate, ResourceTransferPolicy.BatchMode.GREEDY, ResourceTypes.defaultExactBatchSize(type))
                : parameter;
    }
}
