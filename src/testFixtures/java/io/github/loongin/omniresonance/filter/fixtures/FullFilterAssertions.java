// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter.fixtures;

import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Strict test assertion adapter: any non-exact, non-item or component rule fails instead of being projected away. */
public final class FullFilterAssertions {
    private FullFilterAssertions() {}

    public static Set<ResourceLocation> itemIds(ResourceFilterPreset preset) {
        Set<ResourceLocation> ids = new HashSet<>();
        for (ResourceFilterRule rule : preset.rules()) {
            if (!(rule instanceof ResourceFilterRule.Match match)
                    || !match.resourceTypeId().equals(ResourceTypes.ITEM)
                    || !(match.selector() instanceof ResourceFilterRule.Exact exact)
                    || !match.components().isIdOnly())
                throw new AssertionError("Expected only exact ID-only item rules");
            if (!ids.add(exact.resourceId())) throw new AssertionError("Duplicate exact rule");
        }
        return ids;
    }
}
