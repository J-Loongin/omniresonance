// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.FilterRulePreview;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

class RuleIconPreviewTest {
    private static FilterRulePreview tag() {
        return new FilterRulePreview(FilterRulePreview.Kind.TAG, ResourceTypes.ITEM, "c:ingots");
    }

    private static RulePreviewSource source(List<ResourceLocation> ids, AtomicInteger reads) {
        return new RulePreviewSource() {
            public Iterator<ResourceLocation> open(FilterRulePreview preview) {
                return ids.iterator();
            }

            public RulePreviewImage image(ResourceLocation type, ResourceLocation id) {
                reads.incrementAndGet();
                return RulePreviewImage.item(
                        new ItemStack(id.getPath().equals("gold_ingot") ? Items.GOLD_INGOT : Items.IRON_INGOT));
            }
        };
    }

    @Test
    void cyclesEveryTwentyTicksAndHoverPausesWithoutResettingTheSequence() {
        var reads = new AtomicInteger();
        var preview = new RuleIconPreview(
                tag(),
                source(
                        List.of(
                                ResourceLocation.parse("minecraft:iron_ingot"),
                                ResourceLocation.parse("minecraft:gold_ingot"),
                                ResourceLocation.parse("minecraft:copper_ingot"),
                                ResourceLocation.parse("test:tin_ingot")),
                        reads));
        preview.prepare(64);
        assertEquals(4, reads.get());
        assertTrue(preview.current().item().is(Items.IRON_INGOT));
        for (int i = 0; i < 19; i++) preview.tickAnimation();
        assertTrue(preview.current().item().is(Items.IRON_INGOT));
        for (int i = 0; i < 100; i++) {
            preview.hover();
            preview.tickAnimation();
        }
        assertTrue(preview.current().item().is(Items.IRON_INGOT));
        preview.tickAnimation();
        assertTrue(preview.current().item().is(Items.GOLD_INGOT));
        for (int i = 0; i < 60; i++) preview.tickAnimation();
        assertTrue(preview.current().item().is(Items.IRON_INGOT));
        assertEquals(4, reads.get());
    }

    @Test
    void largeTagsStopAtTwentyFourSamplesAndNeverScanDuringAnimation() {
        var reads = new AtomicInteger();
        var ids = new java.util.ArrayList<ResourceLocation>();
        for (int i = 0; i < 10000; i++) ids.add(ResourceLocation.parse("test:resource_" + i));
        var preview = new RuleIconPreview(tag(), source(ids, reads));
        for (int i = 0; i < 200; i++) assertTrue(preview.prepare(4) <= 4);
        assertEquals(24, preview.imageCount());
        assertEquals(25, reads.get());
        assertTrue(preview.grid().more());
        assertEquals(24, preview.grid().images().size());
        for (int i = 0; i < 200; i++) {
            preview.current();
            preview.tickAnimation();
        }
        assertEquals(25, reads.get());
    }

    @Test
    void threeInlineIconsStayFixedAndExactIdsDoNotDuplicateTheirImageOnHover() {
        var ids = List.of(
                ResourceLocation.parse("minecraft:iron_ingot"),
                ResourceLocation.parse("minecraft:gold_ingot"),
                ResourceLocation.parse("minecraft:copper_ingot"));
        var preview = new RuleIconPreview(tag(), source(ids, new AtomicInteger()));
        preview.prepare(64);
        assertEquals(3, preview.inlineCount());
        assertEquals(52, preview.width());
        assertTrue(preview.inlineImage(0).item().is(Items.IRON_INGOT));
        assertTrue(preview.inlineImage(1).item().is(Items.GOLD_INGOT));
        for (int tick = 0; tick < 100; tick++) preview.tickAnimation();
        assertTrue(preview.inlineImage(0).item().is(Items.IRON_INGOT));
        var exact = new RuleIconPreview(
                FilterRulePreview.exactItem("minecraft:iron_ingot"),
                source(List.of(ids.getFirst()), new AtomicInteger()));
        exact.prepare(64);
        assertEquals(1, exact.inlineCount());
        assertEquals(16, exact.width());
        assertNull(exact.grid());
    }

    @Test
    void rightAlignedIconsReserveTheirOwnSpaceAndKeepTheWholeRowClickable() {
        var preview = new RuleIconPreview(
                tag(),
                source(
                        List.of(
                                ResourceLocation.parse("test:a"),
                                ResourceLocation.parse("test:b"),
                                ResourceLocation.parse("test:c")),
                        new AtomicInteger()));
        preview.prepare(64);
        var clicks = new AtomicInteger();
        var row = new TerminalRowButton(
                new TerminalLayout.Rect(10, 20, 300, 20),
                net.minecraft.network.chat.Component.literal("#c:ingots"),
                ignored -> clicks.incrementAndGet());
        row.icon(preview);
        assertEquals(7, row.textLeftInset());
        assertEquals(row.getRight() - 7 - 52 - 6, row.textRightEdge());
        assertEquals("#c:ingots", row.getMessage().getString());
        assertEquals(300, row.getWidth());
        assertEquals(20, row.getHeight());
        assertNull(row.getTooltip());
        row.onPress();
        assertEquals(1, clicks.get());
        row.setSelected(true);
        assertEquals(row.getRight() - 7 - 12 - 52 - 6, row.textRightEdge());
    }

    @Test
    void hoverGridKeepsAStableSnapshotUntilThePointerLeaves() {
        var ids = List.of(
                ResourceLocation.parse("minecraft:iron_ingot"),
                ResourceLocation.parse("minecraft:gold_ingot"),
                ResourceLocation.parse("minecraft:copper_ingot"));
        var preview = new RuleIconPreview(tag(), source(ids, new AtomicInteger()));
        preview.prepare(3);
        preview.hover();
        var first = preview.grid();
        assertEquals(1, first.images().size());
        preview.prepare(64);
        preview.tickAnimation();
        assertTrue(first == preview.grid());
        preview.tickAnimation();
        assertEquals(3, preview.grid().images().size());
    }

    @Test
    void gridUsesSixColumnsAtMostFourRowsAndFitsAllScreenEdges() {
        var image = RulePreviewImage.item(new ItemStack(Items.IRON_INGOT));
        for (int count : new int[] {1, 3, 6, 7, 24}) {
            var grid = new RulePreviewGrid(java.util.Collections.nCopies(count, image), count == 24);
            assertEquals(116, grid.width());
            assertEquals(((count + 5) / 6) * 18 + 8 + (count == 24 ? 6 : 0), grid.height());
            for (int[] size : new int[][] {{320, 240}, {640, 360}, {960, 540}}) {
                for (int[] mouse :
                        new int[][] {{0, 0}, {size[0] - 1, 0}, {0, size[1] - 1}, {size[0] - 1, size[1] - 1}}) {
                    var bounds = grid.bounds(size[0], size[1], mouse[0], mouse[1]);
                    assertTrue(bounds.x() >= 4 && bounds.y() >= 4);
                    assertTrue(bounds.right() <= size[0] - 4 && bounds.bottom() <= size[1] - 4);
                    var last = grid.cell(bounds, count - 1);
                    assertTrue(last.right() <= bounds.right() - 4 && last.bottom() <= bounds.bottom() - 4);
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new RulePreviewGrid(java.util.Collections.nCopies(25, image), false));
    }

    @Test
    void globMatchingResumesWithinItsBudgetAndShowsOnlyMatchingIds() {
        var reads = new AtomicInteger();
        var preview = new RuleIconPreview(
                new FilterRulePreview(FilterRulePreview.Kind.GLOB, ResourceTypes.ITEM, "*:gold_*"),
                source(
                        List.of(
                                ResourceLocation.parse("minecraft:iron_ingot"),
                                ResourceLocation.parse("minecraft:gold_ingot")),
                        reads));
        for (int i = 0; i < 1000; i++) assertTrue(preview.prepare(3) <= 3);
        assertEquals(1, reads.get());
        assertEquals(1, preview.imageCount());
        assertTrue(preview.current().item().is(Items.GOLD_INGOT));
    }

    @Test
    void nativeSamplesUseItemsFluidSpritesAndEnergyWithoutInventoryCapabilities() {
        var source = new RulePreviewSource.RegistrySource(
                io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory.nativeDefaults(), () -> null);
        assertTrue(source.image(ResourceTypes.ITEM, ResourceLocation.parse("minecraft:iron_ingot"))
                .item()
                .is(Items.IRON_INGOT));
        assertFalse(source.image(ResourceTypes.FLUID, ResourceLocation.parse("minecraft:water"))
                .fluid()
                .isEmpty());
        assertEquals(
                EnergyDisplay.TEXTURE,
                source.image(ResourceTypes.ENERGY, ResourceTypes.ENERGY).texture());
        assertNull(source.image(ResourceTypes.ITEM, ResourceLocation.parse("missing:item")));
        assertNull(source.open(new FilterRulePreview(FilterRulePreview.Kind.WHOLE_TYPE, ResourceTypes.CHEMICAL, "")));
    }

    @Test
    void nativeIngotGlobProducesAVisibleExampleWithinFortyClientTicks() {
        var registries = net.minecraft.core.HolderLookup.Provider.create(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY.stream()
                        .map(registry -> registry.asLookup()));
        var cache = new RulePreviewCache(new RulePreviewSource.RegistrySource(
                io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory.nativeDefaults(), () -> registries));
        var preview =
                cache.get(new FilterRulePreview(FilterRulePreview.Kind.GLOB, ResourceTypes.ITEM, "minecraft:*_ingot"));
        for (int i = 0; i < 40 && preview.imageCount() == 0; i++) cache.tick();
        assertTrue(preview.imageCount() > 0, "Common native glob did not produce a visible example within forty ticks");
    }

    @Test
    void unavailableAndEmptyHaveDistinctFallbacksWhileReferencesNeverResolveResources() {
        var reads = new AtomicInteger();
        var source = source(List.of(), reads);
        var empty = new RuleIconPreview(tag(), source);
        empty.prepare(10);
        var missing = new RuleIconPreview(FilterRulePreview.NONE, source);
        var reference = new RuleIconPreview(FilterRulePreview.REFERENCE, source);
        assertTrue(empty.current().item().isEmpty());
        assertTrue(missing.current().item().is(Items.BARRIER));
        assertTrue(reference.current().item().is(Items.CHAIN));
        assertEquals(0, reads.get());
    }

    @Test
    void cacheIsBoundedAndClearingDropsOldRegistrySamples() {
        var cache =
                new RulePreviewCache(source(List.of(ResourceLocation.parse("test:iron_ingot")), new AtomicInteger()));
        var old = cache.get(tag());
        for (int i = 0; i < 1000; i++) {
            cache.beginRows();
            cache.get(new FilterRulePreview(FilterRulePreview.Kind.EXACT, ResourceTypes.ITEM, "test:r" + i));
        }
        assertEquals(128, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
        assertNotSame(old, cache.get(tag()));
    }
}
