// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Untrusted typed rule intent; contains no client-authored component values or canonical bytes. */
public sealed interface ResourceRuleIntent {
    record Match(
            ResourceLocation typeId,
            ResourceFilterRule.Selector selector,
            ComponentCondition.Mode mode,
            Set<ResourceLocation> selectedKeys,
            @Nullable UUID sampleToken)
            implements ResourceRuleIntent {
        public Match {
            Objects.requireNonNull(typeId);
            Objects.requireNonNull(selector);
            Objects.requireNonNull(mode);
            if (typeId.toString().length() > 128
                    || selectedKeys.size() > ResourceFilterPreset.MAX_ENTRIES
                    || mode != ComponentCondition.Mode.SELECTED && !selectedKeys.isEmpty())
                throw new IllegalArgumentException("Invalid rule intent bounds");
            selectedKeys = Set.copyOf(selectedKeys);
        }
    }

    record Reference(UUID presetId) implements ResourceRuleIntent {
        public Reference {
            Objects.requireNonNull(presetId);
        }
    }
}
