// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class FilterPresetPreviewTest {
    @Test
    void samplingOnlyVisitsTwentyFourDirectRulesAndNeverExpandsReferences() {
        var reads = new AtomicInteger();
        var reference = new ResourceFilterRule.Reference(new UUID(1, 2), new UUID(2, 3));
        var rules = new java.util.AbstractList<ResourceFilterRule>() {
            public int size() {
                return 10000;
            }

            public ResourceFilterRule get(int index) {
                reads.incrementAndGet();
                return reference;
            }
        };
        var preview = FilterPresetPreview.from(rules);
        assertEquals(24, reads.get());
        assertEquals(24, preview.rules().size());
        assertTrue(preview.truncated());
        assertTrue(preview.rules().stream().allMatch(FilterRulePreview.REFERENCE::equals));
    }

    @Test
    void fullPageWithMaximumNamesAndLongSelectorsStaysWithinTheRegisteredFrameBound() {
        var rules = new ArrayList<ResourceFilterRule>();
        for (int i = 0; i < 24; i++)
            rules.add(new ResourceFilterRule.Match(
                    new UUID(1, i),
                    ResourceTypes.ITEM,
                    ResourceFilterRule.Selector.exact(ResourceLocation.parse("test:" + "x".repeat(240) + i)),
                    ComponentCondition.idOnly()));
        var preview = FilterPresetPreview.from(rules);
        assertTrue(preview.truncated());
        var entries = new ArrayList<FilterPresetSummary>();
        for (int i = 0; i < 128; i++)
            entries.add(new FilterPresetSummary(new UUID(2, i), "😀".repeat(64), 1, 24, true, preview));
        var page = new FilterPresetPage(entries, 0, 128, 1);
        var wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            page.write(wire);
            assertTrue(wire.readableBytes() < 240000);
            assertEquals(page, FilterPresetPage.read(wire));
            assertEquals(0, wire.readableBytes());
        } finally {
            wire.release();
        }
    }

    @Test
    void smallWholeLibraryAllowanceOnlyTruncatesDisplayMetadata() {
        var rules = List.<ResourceFilterRule>of(new ResourceFilterRule.Match(
                new UUID(1, 1),
                ResourceTypes.ITEM,
                ResourceFilterRule.Selector.exact(ResourceLocation.parse("minecraft:iron_ingot")),
                ComponentCondition.idOnly()));
        for (int allowance : new int[] {4, 16, 64, 1536}) {
            var preview = FilterPresetPreview.from(rules, allowance);
            var wire = new FriendlyByteBuf(Unpooled.buffer());
            try {
                preview.write(wire);
                assertTrue(wire.readableBytes() <= allowance);
                assertEquals(preview, FilterPresetPreview.read(wire));
            } finally {
                wire.release();
            }
        }
        assertEquals(1, rules.size());
        assertThrows(
                IllegalArgumentException.class,
                () -> new FilterPresetPreview(java.util.Collections.nCopies(25, FilterRulePreview.NONE), false));
        var wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            wire.writeBoolean(false).writeVarInt(25);
            assertThrows(io.netty.handler.codec.DecoderException.class, () -> FilterPresetPreview.read(wire));
        } finally {
            wire.release();
        }
    }
}
