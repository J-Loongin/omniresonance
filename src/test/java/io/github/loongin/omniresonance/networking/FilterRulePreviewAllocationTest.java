// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.lang.management.ManagementFactory;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class FilterRulePreviewAllocationTest {
    private static volatile FilterRulePreview sink;

    @Test
    void rejectedLongSelectorsDoNotAllocateFullIdStringsForEverySummary() {
        var id = ResourceLocation.parse("test:" + "x".repeat(65530));
        var rules = new ResourceFilterRule[] {
            new ResourceFilterRule.Match(
                    new UUID(1, 1),
                    ResourceTypes.ITEM,
                    ResourceFilterRule.Selector.exact(id),
                    ComponentCondition.idOnly()),
            new ResourceFilterRule.Match(
                    new UUID(1, 2),
                    ResourceTypes.FLUID,
                    ResourceFilterRule.Selector.tag(id),
                    ComponentCondition.idOnly())
        };
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(bean.isThreadAllocatedMemorySupported(), "Temurin 21 allocation counters required");
        boolean enabled = bean.isThreadAllocatedMemoryEnabled();
        if (!enabled) bean.setThreadAllocatedMemoryEnabled(true);
        try {
            for (int i = 0; i < 2048; i++) sink = FilterRulePreview.from(rules[i % 2]);
            long thread = Thread.currentThread().threadId();
            long before = bean.getThreadAllocatedBytes(thread);
            for (int i = 0; i < 256; i++) sink = FilterRulePreview.from(rules[i % 2]);
            long allocated = bean.getThreadAllocatedBytes(thread) - before;
            assertEquals(FilterRulePreview.NONE, sink);
            assertTrue(allocated < 524288, "256 rejected selectors allocated " + allocated + " bytes");
        } finally {
            if (!enabled) bean.setThreadAllocatedMemoryEnabled(false);
        }
    }

    @Test
    void selectorLengthBoundaryPreservesExactAndTagIdentity() {
        for (int length : new int[] {255, 256, 257}) {
            var id = ResourceLocation.fromNamespaceAndPath("a", "x".repeat(length - 2));
            for (var selector : new ResourceFilterRule.Selector[] {
                ResourceFilterRule.Selector.exact(id), ResourceFilterRule.Selector.tag(id)
            }) {
                var preview = FilterRulePreview.from(new ResourceFilterRule.Match(
                        new UUID(2, length), ResourceTypes.ITEM, selector, ComponentCondition.idOnly()));
                if (length > 256) assertEquals(FilterRulePreview.NONE, preview);
                else assertEquals(id.toString(), preview.selector());
            }
        }
    }
}
