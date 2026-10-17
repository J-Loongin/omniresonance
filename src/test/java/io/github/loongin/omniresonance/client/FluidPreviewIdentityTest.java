// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.loongin.omniresonance.networking.FilterPresetPreview;
import io.github.loongin.omniresonance.networking.FilterRulePreview;
import io.github.loongin.omniresonance.transfer.FluidVariant;
import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

class FluidPreviewIdentityTest {
    private static final ResourceLocation WATER = ResourceLocation.parse("minecraft:water"),
            FLOWING = ResourceLocation.parse("minecraft:flowing_water"),
            LAVA = ResourceLocation.parse("minecraft:lava"),
            FLOWING_LAVA = ResourceLocation.parse("minecraft:flowing_lava");
    private static final RulePreviewSource.RegistrySource NATIVE =
            new RulePreviewSource.RegistrySource(ResourceAdapterDirectory.nativeDefaults(), () -> null);

    private static RulePreviewSource source(List<ResourceLocation> members) {
        return new RulePreviewSource() {
            public Iterator<ResourceLocation> open(FilterRulePreview rule) {
                return rule.kind() == FilterRulePreview.Kind.EXACT
                        ? List.of(ResourceLocation.parse(rule.selector())).iterator()
                        : members.iterator();
            }

            public ResourceLocation displayId(ResourceLocation type, ResourceLocation id) {
                return NATIVE.displayId(type, id);
            }

            public RulePreviewImage image(ResourceLocation type, ResourceLocation id) {
                return type.equals(ResourceTypes.FLUID)
                        ? NATIVE.image(type, id)
                        : RulePreviewImage.fluid(new FluidStack(Fluids.WATER, 1000));
            }
        };
    }

    @Test
    void nativePairGroupingDoesNotChangeAuthoritativeFluidKeysOrOtherResourceTypes() {
        assertEquals(WATER, NATIVE.displayId(ResourceTypes.FLUID, FLOWING));
        assertEquals(WATER, NATIVE.displayId(ResourceTypes.FLUID, WATER));
        assertEquals(LAVA, NATIVE.displayId(ResourceTypes.FLUID, FLOWING_LAVA));
        assertEquals(FLOWING, NATIVE.displayId(ResourceTypes.ITEM, FLOWING));
        var provider =
                HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(registry -> registry.asLookup()));
        assertNotEquals(
                FluidVariant.from(new FluidStack(Fluids.WATER, 1000), provider).key(),
                FluidVariant.from(new FluidStack(Fluids.FLOWING_WATER, 1000), provider)
                        .key());
    }

    @Test
    void tagRulesMergeOnlySourceFlowingPairsAndUseTheCompactGrid() {
        var preview = new RuleIconPreview(
                new FilterRulePreview(FilterRulePreview.Kind.TAG, ResourceTypes.FLUID, "test:liquids"),
                source(List.of(FLOWING, WATER, LAVA, FLOWING_LAVA)));
        for (int i = 0; i < 50; i++) preview.prepare(2);
        assertEquals(2, preview.imageCount());
        assertEquals(WATER, preview.identity(0).id());
        assertEquals(LAVA, preview.identity(1).id());
        assertEquals(44, preview.grid().width());
        assertFalse(preview.grid().more());
    }

    @Test
    void presetAggregationUsesTheSameDisplayGroupingWithoutMergingChemicalIdentity() {
        var preview = new PresetIconPreview(
                new FilterPresetPreview(
                        List.of(
                                new FilterRulePreview(
                                        FilterRulePreview.Kind.EXACT, ResourceTypes.FLUID, WATER.toString()),
                                new FilterRulePreview(
                                        FilterRulePreview.Kind.EXACT, ResourceTypes.FLUID, FLOWING.toString()),
                                new FilterRulePreview(
                                        FilterRulePreview.Kind.EXACT, ResourceTypes.CHEMICAL, WATER.toString())),
                        false),
                source(List.of()));
        for (int i = 0; i < 100; i++) preview.prepare(4);
        assertEquals(2, preview.imageCount());
        assertEquals(44, preview.grid().width());
    }

    @Test
    void exactSelectorsKeepTheRequestedIdAndGlobMatchesBeforeDisplayGrouping() {
        var exact = new FilterRulePreview(FilterRulePreview.Kind.EXACT, ResourceTypes.FLUID, FLOWING.toString());
        var preview = new RuleIconPreview(exact, source(List.of()));
        preview.prepare(100);
        assertEquals(FLOWING.toString(), exact.selector());
        assertEquals(Fluids.FLOWING_WATER, preview.current().fluid().getFluid());
        assertNull(preview.grid());
        var glob = new RuleIconPreview(
                new FilterRulePreview(FilterRulePreview.Kind.GLOB, ResourceTypes.FLUID, "minecraft:flowing_*"),
                source(List.of(WATER, FLOWING, LAVA, FLOWING_LAVA)));
        for (int i = 0; i < 100; i++) glob.prepare(128);
        assertEquals(2, glob.imageCount());
        assertEquals(WATER, glob.identity(0).id());
        assertEquals(LAVA, glob.identity(1).id());
    }
}
