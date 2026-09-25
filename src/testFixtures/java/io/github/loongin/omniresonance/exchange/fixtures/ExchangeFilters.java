// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange.fixtures;

import io.github.loongin.omniresonance.exchange.ExchangeFilterSnapshot;
import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit broad preset for tests of transfer behavior unrelated to filter admission. */
public final class ExchangeFilters {
    private ExchangeFilters() {}

    public static ExchangeFilterSnapshot allResources() {
        return resources(ResourceTypes.ITEM, ResourceTypes.FLUID, ResourceTypes.ENERGY);
    }

    public static ExchangeFilterSnapshot resources(net.minecraft.resources.ResourceLocation... types) {
        var rules = new ArrayList<ResourceFilterRule>();
        int i = 0;
        for (var type : List.of(types))
            rules.add(new ResourceFilterRule.Match(
                    new UUID(982, ++i), type, ResourceFilterRule.Selector.wholeType(), ComponentCondition.idOnly()));
        var preset = new ResourceFilterPreset(new UUID(981, 1), new ManagedName("All test resources"), 0, rules);
        return ExchangeFilterSnapshot.capture(preset.id(), Map.of(preset.id(), preset), 1, types.length);
    }
}
