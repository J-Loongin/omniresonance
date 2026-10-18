// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.FilterPresetPreview;
import io.github.loongin.omniresonance.networking.FilterRulePreview;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

class PresetIconPreviewTest {
    private static final ResourceLocation IRON = ResourceLocation.parse("minecraft:iron_ingot"),
            WATER = ResourceLocation.parse("minecraft:water");
    private static final FilterRulePreview TAG =
            new FilterRulePreview(FilterRulePreview.Kind.TAG, ResourceTypes.ITEM, "c:ingots");
    private static final FilterRulePreview WATER_RULE =
            new FilterRulePreview(FilterRulePreview.Kind.EXACT, ResourceTypes.FLUID, WATER.toString());
    private static final FilterRulePreview ENERGY =
            new FilterRulePreview(FilterRulePreview.Kind.WHOLE_TYPE, ResourceTypes.ENERGY, "");

    private static RulePreviewSource source(AtomicInteger reads) {
        return new RulePreviewSource() {
            public Iterator<ResourceLocation> open(FilterRulePreview preview) {
                if (preview.kind() == FilterRulePreview.Kind.TAG) {
                    return new Iterator<>() {
                        int index;

                        public boolean hasNext() {
                            return index < 10000;
                        }

                        public ResourceLocation next() {
                            return index++ == 0 ? IRON : ResourceLocation.parse("test:ingot_" + index);
                        }
                    };
                }
                if (preview.kind() == FilterRulePreview.Kind.REFERENCE)
                    throw new AssertionError("References must not be resolved");
                return List.of(
                                preview.kind() == FilterRulePreview.Kind.WHOLE_TYPE
                                        ? preview.type()
                                        : ResourceLocation.parse(preview.selector()))
                        .iterator();
            }

            public RulePreviewImage image(ResourceLocation type, ResourceLocation id) {
                reads.incrementAndGet();
                return new RulePreviewImage(
                        ItemStack.EMPTY,
                        FluidStack.EMPTY,
                        EnergyDisplay.TEXTURE,
                        EnergyDisplay.TINT,
                        Component.literal(type + "/" + id));
            }
        };
    }

    private static void prepare(PresetIconPreview preview) {
        for (int tick = 0; tick < 200; tick++) assertTrue(preview.prepare(256) <= 256);
    }

    @Test
    void takesOneExampleFromEachDirectRuleBeforeFillingALargeTag() {
        var reads = new AtomicInteger();
        var preview =
                new PresetIconPreview(new FilterPresetPreview(List.of(TAG, WATER_RULE, ENERGY), false), source(reads));
        prepare(preview);
        assertEquals(ResourceTypes.ITEM + "/" + IRON, preview.image(0).name().getString());
        assertEquals(ResourceTypes.FLUID + "/" + WATER, preview.image(1).name().getString());
        assertEquals(
                ResourceTypes.ENERGY + "/" + ResourceTypes.ENERGY,
                preview.image(2).name().getString());
        assertEquals(24, preview.imageCount());
        assertTrue(preview.grid().more());
        assertTrue(reads.get() <= 27);
    }

    @Test
    void overlappingRulesDeduplicateByTypeAndIdWhileReferencesRemainASinglePlaceholder() {
        var exact = FilterRulePreview.exactItem(IRON.toString());
        var otherType = new FilterRulePreview(FilterRulePreview.Kind.EXACT, ResourceTypes.FLUID, IRON.toString());
        var preview = new PresetIconPreview(
                new FilterPresetPreview(
                        List.of(exact, exact, otherType, FilterRulePreview.REFERENCE, FilterRulePreview.REFERENCE),
                        false),
                source(new AtomicInteger()));
        prepare(preview);
        assertEquals(3, preview.imageCount());
        var names = new java.util.HashSet<String>();
        int references = 0;
        for (int i = 0; i < preview.imageCount(); i++) {
            if (preview.image(i).name() != null)
                names.add(preview.image(i).name().getString());
            if (preview.image(i).item().is(Items.CHAIN)) references++;
        }
        assertEquals(java.util.Set.of(ResourceTypes.ITEM + "/" + IRON, ResourceTypes.FLUID + "/" + IRON), names);
        assertEquals(1, references);
    }

    @Test
    void firstPassDoesNotDecodeAnEntireTagWhileAnotherRuleIsStillPreparing() {
        var reads = new AtomicInteger();
        var common = source(reads);
        var delayed = new RulePreviewSource() {
            public Iterator<ResourceLocation> open(FilterRulePreview rule) {
                return rule.kind() == FilterRulePreview.Kind.GLOB
                        ? List.of(ResourceLocation.parse("test:" + "x".repeat(10000)))
                                .iterator()
                        : common.open(rule);
            }

            public RulePreviewImage image(ResourceLocation type, ResourceLocation id) {
                return common.image(type, id);
            }
        };
        var glob = new FilterRulePreview(FilterRulePreview.Kind.GLOB, ResourceTypes.ITEM, "missing:*");
        var preview = new PresetIconPreview(new FilterPresetPreview(List.of(TAG, glob), false), delayed);
        assertTrue(preview.prepare(256) <= 256);
        assertEquals(1, preview.imageCount());
        assertEquals(1, reads.get(), "Only the first tag example is needed during the first-rule pass");
    }

    @Test
    void everySupplementalRoundIncludesTheSlowMatchingRuleBeforeTheFastRuleAdvances() {
        for (int budget : new int[] {1, 64, 256, 16384}) {
            var source = new RulePreviewSource() {
                public Iterator<ResourceLocation> open(FilterRulePreview rule) {
                    return new Iterator<>() {
                        int next;

                        public boolean hasNext() {
                            return next < 100;
                        }

                        public ResourceLocation next() {
                            return ResourceLocation.parse(
                                    (rule.kind() == FilterRulePreview.Kind.TAG ? "fast:" : "slow:" + "x".repeat(32))
                                            + next++);
                        }
                    };
                }

                public RulePreviewImage image(ResourceLocation type, ResourceLocation id) {
                    return new RulePreviewImage(
                            ItemStack.EMPTY,
                            FluidStack.EMPTY,
                            EnergyDisplay.TEXTURE,
                            EnergyDisplay.TINT,
                            Component.literal(id.toString()));
                }
            };
            var slow = new FilterRulePreview(FilterRulePreview.Kind.GLOB, ResourceTypes.ITEM, "slow:*");
            var preview = new PresetIconPreview(new FilterPresetPreview(List.of(TAG, slow), false), source);
            for (int tick = 0; tick < 20000; tick++) assertTrue(preview.prepare(budget) <= budget);
            assertEquals(24, preview.imageCount());
            for (int i = 0; i < 24; i += 2) {
                var pair = new java.util.HashSet<>(java.util.List.of(
                        preview.image(i).name().getString().split(":")[0],
                        preview.image(i + 1).name().getString().split(":")[0]));
                assertEquals(java.util.Set.of("fast", "slow"), pair, "Preparation cost changed the example round");
            }
        }
    }

    @Test
    void smallBudgetsDoNotLetTheFirstRuleHideOtherTypes() {
        for (int budget = 1; budget <= 4; budget++) {
            var preview = new PresetIconPreview(
                    new FilterPresetPreview(List.of(TAG, WATER_RULE, ENERGY), false), source(new AtomicInteger()));
            for (int i = 0; i < 5000; i++) assertTrue(preview.prepare(budget) <= budget);
            assertTrue(preview.imageCount() >= 3, "No progress with budget " + budget);
            var firstExamples = new java.util.HashSet<String>();
            for (int i = 0; i < 3; i++)
                firstExamples.add(preview.image(i).name().getString());
            assertEquals(
                    java.util.Set.of(
                            ResourceTypes.ITEM + "/" + IRON,
                            ResourceTypes.FLUID + "/" + WATER,
                            ResourceTypes.ENERGY + "/" + ResourceTypes.ENERGY),
                    firstExamples);
        }
    }

    @Test
    void emptyPresetsDoNotScanAndTruncatedSamplesRetainAnOverflowMarker() {
        var reads = new AtomicInteger();
        var empty = new PresetIconPreview(FilterPresetPreview.EMPTY, source(reads));
        prepare(empty);
        assertEquals(0, reads.get());
        var truncated = new PresetIconPreview(new FilterPresetPreview(List.of(ENERGY), true), source(reads));
        prepare(truncated);
        assertEquals(1, truncated.imageCount());
        assertTrue(truncated.grid().more());
    }
}
