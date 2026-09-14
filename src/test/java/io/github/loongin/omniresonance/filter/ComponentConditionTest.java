// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.transfer.EnergyVariant;
import io.github.loongin.omniresonance.transfer.FluidVariant;
import io.github.loongin.omniresonance.transfer.ItemVariant;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.ResourceVariant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

final class ComponentConditionTest {
    private static final UUID ROOT = new UUID(0, 1);
    private static final HolderLookup.Provider PROVIDER =
            HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(registry -> registry.asLookup()));

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }

    private static ResourceFilterRule.Match rule(
            long rule, ResourceLocation type, ResourceFilterRule.Selector selector, ComponentCondition condition) {
        return new ResourceFilterRule.Match(new UUID(0, rule), type, selector, condition);
    }

    private static ResourceFilterCompiler.Compiled compile(
            ResourceFilterCompiler.Tags tags, ResourceFilterRule... rules) {
        var preset = new ResourceFilterPreset(ROOT, new ManagedName("Test"), 4, List.of(rules));
        return ResourceFilterCompiler.compile(
                ROOT, new ResourceFilterCompiler.OwnerSnapshot(ROOT, Map.of(ROOT, preset)), tags);
    }

    private static ResourceFilterCompiler.Compiled compile(ResourceFilterRule... rules) {
        return compile((type, tag) -> ResourceFilterCompiler.TagSnapshot.missing(1), rules);
    }

    private static boolean allows(ResourceFilterCompiler.Compiled compiled, ResourceVariant candidate) {
        return compiled.allows(compiled.prepareCandidate(candidate), FilterMode.WHITELIST);
    }

    @Test
    void exactAndGlobSupportItemAndFluidAndNeverMatchUnconfiguredTypes() {
        var stone = ItemVariant.from(new ItemStack(Items.STONE), PROVIDER);
        var water = FluidVariant.from(new FluidStack(Fluids.WATER, 1), PROVIDER);
        var exact = compile(rule(
                1,
                ResourceTypes.ITEM,
                ResourceFilterRule.Selector.exact(id("minecraft:stone")),
                ComponentCondition.idOnly()));
        assertTrue(allows(exact, stone));
        assertFalse(allows(exact, water));
        assertFalse(exact.preparationCost(stone).decodesComponents());
        var glob = compile(rule(
                1,
                ResourceTypes.FLUID,
                ResourceFilterRule.Selector.glob("minecraft:w?ter"),
                ComponentCondition.idOnly()));
        assertTrue(allows(glob, water));
        assertFalse(allows(glob, stone));
        assertTrue(compile(rule(
                        1,
                        ResourceTypes.ITEM,
                        ResourceFilterRule.Selector.exact(id("absent:resource")),
                        ComponentCondition.idOnly()))
                .valid());
    }

    @Test
    void manyConfiguredRulesStillYieldAtTheRequestedBudget() {
        List<ResourceFilterRule> rules = new ArrayList<>();
        for (int i = 0; i < 10000; i++)
            rules.add(rule(
                    i,
                    ResourceTypes.ITEM,
                    ResourceFilterRule.Selector.exact(id("minecraft:dirt")),
                    ComponentCondition.idOnly()));
        var compiled = compile(rules.toArray(ResourceFilterRule[]::new));
        var stone = ItemVariant.from(new ItemStack(Items.STONE), PROVIDER);
        var evaluation = compiled.evaluate(compiled.prepareCandidate(stone), FilterMode.WHITELIST);
        long work = 0;
        while (!evaluation.done()) {
            int used = evaluation.step(13);
            assertTrue(used <= 13);
            work += used;
        }
        assertFalse(evaluation.allowed());
        assertEquals(20001, work);
    }

    @Test
    void componentPreparationOnlyDependsOnCandidateType() {
        var water = FluidVariant.from(new FluidStack(Fluids.WATER, 1), PROVIDER);
        var stone = ItemVariant.from(new ItemStack(Items.STONE), PROVIDER);
        var compiled = compile(
                rule(1, ResourceTypes.ITEM, ResourceFilterRule.Selector.wholeType(), ComponentCondition.idOnly()),
                rule(2, ResourceTypes.FLUID, ResourceFilterRule.Selector.wholeType(), ComponentCondition.full(water)));
        assertFalse(compiled.preparationCost(stone).decodesComponents());
        assertTrue(compiled.preparationCost(water).decodesComponents());
        assertTrue(compiled.prepareCandidate(stone).componentKeys().isEmpty());
    }

    @Test
    void failedTagResolutionRetainsUnknownRevisionForRecovery() {
        var tagId = id("c:stones");
        var key = new ResourceFilterCompiler.TagKey(ResourceTypes.ITEM, tagId);
        var rule = rule(1, ResourceTypes.ITEM, ResourceFilterRule.Selector.tag(tagId), ComponentCondition.idOnly());
        ResourceFilterCompiler.Tags throwsResolution = (type, tag) -> {
            throw new IllegalStateException("Unavailable snapshot");
        };
        ResourceFilterCompiler.Tags nullResolution = (type, tag) -> null;
        for (ResourceFilterCompiler.Tags resolver : List.of(throwsResolution, nullResolution)) {
            var failed = compile(resolver, rule);
            assertFalse(failed.valid());
            assertEquals(-1L, failed.tagDependencies().get(key));
            var restored = compile(
                    (type, tag) -> new ResourceFilterCompiler.TagSnapshot(true, 7, List.of(id("minecraft:stone"))),
                    rule);
            assertEquals(7L, restored.tagDependencies().get(key));
            assertTrue(allows(restored, ItemVariant.from(new ItemStack(Items.STONE), PROVIDER)));
        }
    }

    @Test
    void genericKeysKeepIdMatchingIndependentOfOtherComponentRules() {
        var stone = ItemVariant.from(new ItemStack(Items.STONE), PROVIDER);
        var water = FluidVariant.from(new FluidStack(Fluids.WATER, 1), PROVIDER);
        for (ResourceVariant nativeVariant : List.of(stone, water)) {
            ResourceVariant wrapped = nativeVariant::key;
            ResourceLocation type = nativeVariant.key().typeId();
            var exact = rule(
                    1,
                    type,
                    ResourceFilterRule.Selector.exact(
                            type.equals(ResourceTypes.ITEM) ? id("minecraft:stone") : id("minecraft:water")),
                    ComponentCondition.idOnly());
            var unrelated = rule(
                    2,
                    type,
                    ResourceFilterRule.Selector.exact(id("absent:resource")),
                    ComponentCondition.full(nativeVariant));
            var idOnly = compile(exact);
            var withFull = compile(exact, unrelated);
            var fullFirst = compile(unrelated, exact);
            assertTrue(allows(idOnly, wrapped));
            assertTrue(allows(withFull, wrapped));
            assertTrue(allows(fullFirst, wrapped));
            assertTrue(idOnly.preparationCost(wrapped).decodesComponents());
            assertFalse(idOnly.preparationCost(nativeVariant).decodesComponents());
            assertEquals(
                    nativeVariant.key().encodedSizeBytes(),
                    idOnly.preparationCost(wrapped).maximumInputBytes());
        }
    }

    @Test
    void tagsDistinguishMissingEmptyFailedAndReloaded() {
        var rule = rule(
                1, ResourceTypes.ITEM, ResourceFilterRule.Selector.tag(id("c:stones")), ComponentCondition.idOnly());
        var stone = ItemVariant.from(new ItemStack(Items.STONE), PROVIDER);
        var matching =
                rule(2, ResourceTypes.ITEM, ResourceFilterRule.Selector.wholeType(), ComponentCondition.idOnly());
        assertFalse(compile(rule, matching).valid());
        assertFalse(compile(
                        (type, tag) -> {
                            throw new IllegalStateException("Unavailable snapshot");
                        },
                        matching,
                        rule)
                .valid());
        var empty = compile((type, tag) -> new ResourceFilterCompiler.TagSnapshot(true, 3, List.of()), rule);
        assertTrue(empty.valid());
        assertFalse(allows(empty, stone));
        assertTrue(empty.allows(empty.prepareCandidate(stone), FilterMode.BLACKLIST));
        var restored = compile(
                (type, tag) -> new ResourceFilterCompiler.TagSnapshot(true, 4, List.of(id("minecraft:stone"))), rule);
        assertTrue(allows(restored, stone));
        assertEquals(4L, restored.tagDependencies().values().iterator().next());
        assertThrows(
                UnsupportedOperationException.class,
                () -> restored.tagDependencies().clear());
    }

    @Test
    void sampledFullAndSelectedComponentsAreAndConditionsAndRulesAreOr() {
        ItemStack stack = new ItemStack(Items.STONE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("A"));
        var sample = ItemVariant.from(stack, PROVIDER);
        var full = ComponentCondition.full(sample);
        Set<ResourceLocation> selected = new HashSet<>(Set.of(id("minecraft:custom_name")));
        var partial = ComponentCondition.selected(sample, selected);
        selected.clear();
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        var extra = ItemVariant.from(stack, PROVIDER);
        var selector = ResourceFilterRule.Selector.exact(id("minecraft:stone"));
        assertFalse(allows(compile(rule(1, ResourceTypes.ITEM, selector, full)), extra));
        assertTrue(allows(compile(rule(1, ResourceTypes.ITEM, selector, partial)), extra));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("B"));
        assertFalse(allows(compile(rule(1, ResourceTypes.ITEM, selector, partial)), ItemVariant.from(stack, PROVIDER)));
        assertTrue(allows(compile(rule(1, ResourceTypes.ITEM, selector, partial)), sample));
        assertFalse(allows(
                compile(rule(1, ResourceTypes.ITEM, ResourceFilterRule.Selector.exact(id("minecraft:dirt")), partial)),
                sample));
        assertTrue(allows(
                compile(rule(1, ResourceTypes.ITEM, selector, full), rule(2, ResourceTypes.ITEM, selector, partial)),
                extra));
        assertThrows(
                UnsupportedOperationException.class,
                () -> FilterResourceSample.capture(sample).componentKeys().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> ComponentCondition.selected(sample, Set.of(id("minecraft:missing"))));
    }

    @Test
    void fluidSelectedDataPreservesNbtTypeAndListOrder() {
        FluidStack stack = new FluidStack(Fluids.WATER, 1);
        CompoundTag nativeData = new CompoundTag();
        nativeData.putInt("number", 1);
        nativeData.putIntArray("ordered", new int[] {1, 2});
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nativeData));
        var condition =
                ComponentCondition.selected(FluidVariant.from(stack, PROVIDER), Set.of(id("minecraft:custom_data")));
        var compiled = compile(rule(1, ResourceTypes.FLUID, ResourceFilterRule.Selector.wholeType(), condition));
        assertTrue(allows(compiled, FluidVariant.from(stack, PROVIDER)));
        nativeData.putLong("number", 1);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nativeData));
        assertFalse(allows(compiled, FluidVariant.from(stack, PROVIDER)));
        nativeData.putInt("number", 1);
        nativeData.putIntArray("ordered", new int[] {2, 1});
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nativeData));
        assertFalse(allows(compiled, FluidVariant.from(stack, PROVIDER)));
        stack.remove(DataComponents.CUSTOM_DATA);
        assertFalse(allows(compiled, FluidVariant.from(stack, PROVIDER)));
    }

    @Test
    void largeCanonicalComponentsMatchWithByteBudgetAndDeclaredPreparationSize() {
        FluidStack stack = new FluidStack(Fluids.WATER, 1);
        CompoundTag data = new CompoundTag();
        data.putByteArray("large", new byte[240000]);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        var variant = FluidVariant.from(stack, PROVIDER);
        var compiled = compile(rule(
                1, ResourceTypes.FLUID, ResourceFilterRule.Selector.wholeType(), ComponentCondition.full(variant)));
        assertTrue(compiled.preparationCost(variant).decodesComponents());
        assertTrue(compiled.preparationCost(variant).maximumInputBytes() > 240000);
        assertTrue(compiled.preparationCost(variant).maximumInputBytes() <= 262144 + 128);
        var evaluation = compiled.evaluate(compiled.prepareCandidate(variant), FilterMode.WHITELIST);
        long work = 0;
        while (!evaluation.done()) {
            int used = evaluation.step(31);
            assertTrue(used <= 31);
            work += used;
        }
        assertTrue(evaluation.allowed());
        assertTrue(work >= 240000);
        assertTrue(work < 250000);
    }

    @Test
    void boundsAndOwnershipRejectInvalidCombinations() {
        var match = rule(1, ResourceTypes.ITEM, ResourceFilterRule.Selector.wholeType(), ComponentCondition.idOnly());
        var rules = new ArrayList<ResourceFilterRule>(List.of(match));
        var preset = new ResourceFilterPreset(ROOT, new ManagedName("Test"), 0, rules);
        rules.clear();
        assertEquals(1, preset.rules().size());
        var map = new HashMap<UUID, ResourceFilterPreset>(Map.of(ROOT, preset));
        var owner = new ResourceFilterCompiler.OwnerSnapshot(ROOT, map);
        map.clear();
        assertEquals(1, owner.presets().size());
        assertThrows(UnsupportedOperationException.class, () -> owner.presets().clear());
        assertThrows(UnsupportedOperationException.class, () -> preset.rules().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceFilterPreset(ROOT, new ManagedName("Test"), 0, List.of(match, match)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceFilterPreset(ROOT, new ManagedName("Test"), -1, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceFilterPreset(
                        ROOT, new ManagedName("Test"), 0, java.util.Collections.nCopies(262145, match)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceFilterCompiler.TagSnapshot(
                        true, 0, java.util.Collections.nCopies(262145, id("minecraft:stone"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> rule(
                        1,
                        id("a:" + "x".repeat(127)),
                        ResourceFilterRule.Selector.wholeType(),
                        ComponentCondition.idOnly()));
        assertThrows(
                IllegalArgumentException.class, () -> ResourceFilterRule.Selector.exact(id("a:" + "x".repeat(65534))));
        assertThrows(
                IllegalArgumentException.class,
                () -> rule(
                        1, ResourceTypes.ENERGY, ResourceFilterRule.Selector.glob("*"), ComponentCondition.idOnly()));
        assertThrows(IllegalArgumentException.class, () -> ComponentCondition.full(EnergyVariant.INSTANCE));
    }
}
