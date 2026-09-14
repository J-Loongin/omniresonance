// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.filter.ItemFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterCompiler;
import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.transfer.CanonicalResourceNbt;
import io.github.loongin.omniresonance.transfer.FluidVariant;
import io.github.loongin.omniresonance.transfer.ItemVariant;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.ResourceVariant;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

final class ResourceFilterPresetNbtTest {
    private static final UUID ROOT = new UUID(12, 34);
    private static final HolderLookup.Provider PROVIDER =
            HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(registry -> registry.asLookup()));

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }

    private static ResourceFilterPreset preset(List<ResourceFilterRule> rules) {
        return new ResourceFilterPreset(ROOT, new ManagedName("Test"), 7, rules);
    }

    private static ResourceFilterRule.Match match(
            long number, ResourceLocation type, ResourceFilterRule.Selector selector, ComponentCondition condition) {
        return new ResourceFilterRule.Match(new UUID(0, number), type, selector, condition);
    }

    private static CompoundTag row(CompoundTag tag) {
        return tag.getList("rules", Tag.TAG_COMPOUND).getCompound(0);
    }

    private static ResourceFilterCompiler.Compiled compile(ResourceFilterPreset preset) {
        return ResourceFilterCompiler.compile(
                ROOT,
                new ResourceFilterCompiler.OwnerSnapshot(ROOT, Map.of(ROOT, preset)),
                (type, tag) -> ResourceFilterCompiler.TagSnapshot.missing(1));
    }

    private static boolean allows(ResourceFilterPreset preset, ResourceVariant candidate) {
        var compiled = compile(preset);
        return compiled.allows(compiled.prepareCandidate(candidate), FilterMode.WHITELIST);
    }

    @Test
    void allSelectorsTypesAndReferencesPreserveFields() {
        List<ResourceFilterRule> rules = new ArrayList<>();
        long number = 0;
        for (var type : List.of(ResourceTypes.ITEM, ResourceTypes.FLUID)) {
            for (var selector : List.of(
                    ResourceFilterRule.Selector.wholeType(),
                    ResourceFilterRule.Selector.exact(id("absent:resource")),
                    ResourceFilterRule.Selector.tag(id("absent:tag")),
                    ResourceFilterRule.Selector.glob("minecraft:**a?")))
                rules.add(match(number++, type, selector, ComponentCondition.idOnly()));
        }
        rules.add(match(
                number++, ResourceTypes.ENERGY, ResourceFilterRule.Selector.wholeType(), ComponentCondition.idOnly()));
        rules.add(new ResourceFilterRule.Reference(new UUID(0, number), new UUID(45, 67)));
        var encoded = ResourceFilterPresetNbt.encode(preset(rules));
        var decoded = ResourceFilterPresetNbt.decode(encoded);
        assertEquals(ROOT, decoded.id());
        assertEquals(7, decoded.revision());
        assertEquals(new ManagedName("Test"), decoded.name());
        assertEquals(encoded, ResourceFilterPresetNbt.encode(decoded));
        assertEquals(10, decoded.rules().size());
        assertFalse(compile(decoded).valid());
        for (var rule : List.of(rules.get(2), rules.get(9))) {
            var missing = ResourceFilterPresetNbt.decode(ResourceFilterPresetNbt.encode(preset(List.of(rule))));
            assertFalse(compile(missing).valid());
        }
        assertTrue(
                compile(ResourceFilterPresetNbt.decode(ResourceFilterPresetNbt.encode(preset(List.of(rules.get(1))))))
                        .valid());
    }

    @Test
    void nativeFullAndSelectedRoundTripKeepsTypesMatchingAndOwnership() {
        CompoundTag data = new CompoundTag();
        data.putInt("number", 1);
        data.putIntArray("array", new int[] {1, 2});
        data.putByteArray("bytes", new byte[] {3});
        data.putLongArray("longs", new long[] {4});
        ListTag list = new ListTag();
        list.add(net.minecraft.nbt.ShortTag.valueOf((short) 5));
        data.put("list", list);
        ItemStack item = new ItemStack(Items.STONE);
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        FluidStack fluid = new FluidStack(Fluids.WATER, 123);
        fluid.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        for (var sample :
                List.<ResourceVariant>of(ItemVariant.from(item, PROVIDER), FluidVariant.from(fluid, PROVIDER))) {
            for (var condition : List.of(
                    ComponentCondition.full(sample),
                    ComponentCondition.selected(sample, Set.of(id("minecraft:custom_data"))))) {
                var original = preset(
                        List.of(match(1, sample.key().typeId(), ResourceFilterRule.Selector.wholeType(), condition)));
                var encoded = ResourceFilterPresetNbt.encode(original);
                var restored = ResourceFilterPresetNbt.decode(encoded);
                var snapshot = ((ResourceFilterRule.Match) restored.rules().getFirst())
                        .components()
                        .persistenceSnapshot();
                assertEquals(condition.persistenceSnapshot(), snapshot);
                assertTrue(allows(restored, sample));
                encoded.getList("rules", Tag.TAG_COMPOUND).clear();
                assertTrue(allows(restored, sample));
                var copy = ResourceFilterPresetNbt.encode(restored);
                row(copy).getCompound("components").remove("mode");
                assertTrue(allows(restored, sample));
                CompoundTag changed = data.copy();
                changed.putLong("number", 1);
                item.set(DataComponents.CUSTOM_DATA, CustomData.of(changed));
                fluid.set(DataComponents.CUSTOM_DATA, CustomData.of(changed));
                ResourceVariant candidate = sample.key().typeId().equals(ResourceTypes.ITEM)
                        ? ItemVariant.from(item, PROVIDER)
                        : FluidVariant.from(fluid, PROVIDER);
                assertFalse(allows(restored, candidate));
                item.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
                fluid.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
            }
        }
    }

    @Test
    void strictFieldsTypesIdsAndDiscriminatorsReject() {
        var valid = ResourceFilterPresetNbt.encode(preset(List.of(match(
                1,
                ResourceTypes.ITEM,
                ResourceFilterRule.Selector.exact(id("missing:item")),
                ComponentCondition.idOnly()))));
        List<java.util.function.Consumer<CompoundTag>> mutations = List.of(
                tag -> tag.putString("unknown", "x"),
                tag -> tag.putInt("revision", 7),
                tag -> tag.putLong("revision", -1),
                tag -> tag.putIntArray("preset_id", new int[] {1}),
                tag -> tag.putString("name", ""),
                tag -> row(tag).putString("kind", "Match"),
                tag -> row(tag).putUUID("preset_id", ROOT),
                tag -> row(tag).putString("resource_type_id", "a:" + "b".repeat(127)),
                tag -> row(tag).getCompound("selector").putString("resource_id", "stone"),
                tag -> row(tag).getCompound("selector").putString("resource_id", "a:" + "b".repeat(65534)),
                tag -> row(tag).getCompound("selector").putString("kind", "EXACT"),
                tag -> row(tag).putString("resource_type_id", ResourceTypes.ENERGY.toString()),
                tag -> row(tag).getCompound("components").putString("mode", "FULL"),
                tag -> row(tag).getCompound("components").putInt("canonical", 1),
                tag -> tag.getList("rules", Tag.TAG_COMPOUND).add(row(tag).copy()));
        for (var mutation : mutations) {
            var bad = valid.copy();
            mutation.accept(bad);
            assertThrows(IllegalArgumentException.class, () -> ResourceFilterPresetNbt.decode(bad));
        }
        var glob = valid.copy();
        var selector = row(glob).getCompound("selector");
        selector.remove("resource_id");
        selector.putString("kind", "glob");
        selector.putString("pattern", "*".repeat(257));
        assertThrows(IllegalArgumentException.class, () -> ResourceFilterPresetNbt.decode(glob));
    }

    @Test
    void malformedCanonicalAndDuplicateSelectedKeysReject() {
        var base = ResourceFilterPresetNbt.encode(preset(List.of(
                match(1, ResourceTypes.ITEM, ResourceFilterRule.Selector.wholeType(), ComponentCondition.idOnly()))));
        for (byte[] bytes : List.of(
                new byte[0],
                new byte[] {10},
                new byte[] {10, 0, 0},
                new byte[] {3, 0, 0, 0, 1},
                new byte[] {10, 1, 0, 1, 97, 1, 1, 0, 1, 97, 2, 0},
                new byte[262145])) {
            var bad = base.copy();
            var components = row(bad).getCompound("components");
            components.putString("mode", "full");
            components.putByteArray("canonical", bytes);
            assertThrows(IllegalArgumentException.class, () -> ResourceFilterPresetNbt.decode(bad));
        }
        var selected = base.copy();
        var components = row(selected).getCompound("components");
        components.putString("mode", "selected");
        CompoundTag value = new CompoundTag();
        value.putString("component_id", "minecraft:custom_data");
        value.putByteArray("canonical", CanonicalResourceNbt.encode(new CompoundTag()));
        ListTag values = new ListTag();
        values.add(value);
        values.add(value.copy());
        components.put("selected", values);
        assertThrows(IllegalArgumentException.class, () -> ResourceFilterPresetNbt.decode(selected));
    }

    @Test
    void migrationStableAcrossSetOrderPreservesMaximumIdAndEmptySemantics() {
        var first = id("a:" + "x".repeat(65533));
        var second = id("minecraft:stone");
        var a = new ItemFilterPreset(ROOT, new ManagedName("Test"), 7, new LinkedHashSet<>(List.of(first, second)));
        var b = new ItemFilterPreset(ROOT, new ManagedName("Test"), 7, new LinkedHashSet<>(List.of(second, first)));
        var migrated = ResourceFilterPresetNbt.migrate(a);
        assertEquals(
                ResourceFilterPresetNbt.encode(migrated),
                ResourceFilterPresetNbt.encode(ResourceFilterPresetNbt.migrate(b)));
        var old = OwnerPresetNbt.encode(List.of(a)).getCompound(0);
        var copy = old.copy();
        assertEquals(
                ResourceFilterPresetNbt.encode(migrated),
                ResourceFilterPresetNbt.encode(ResourceFilterPresetNbt.decodeLegacy(old)));
        assertEquals(copy, old);
        assertEquals(ROOT, migrated.id());
        assertEquals(a.name(), migrated.name());
        assertEquals(7, migrated.revision());
        assertEquals(
                Set.of(first, second),
                migrated.rules().stream()
                        .map(rule ->
                                ((ResourceFilterRule.Exact) ((ResourceFilterRule.Match) rule).selector()).resourceId())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                2,
                migrated.rules().stream().map(ResourceFilterRule::id).distinct().count());
        var empty = ResourceFilterPresetNbt.migrate(new ItemFilterPreset(ROOT, a.name(), 7, Set.of()));
        var compiled = compile(empty);
        var candidate = compiled.prepareCandidate(ItemVariant.from(new ItemStack(Items.STONE), PROVIDER));
        assertFalse(compiled.allows(candidate, FilterMode.WHITELIST));
        assertTrue(compiled.allows(candidate, FilterMode.BLACKLIST));
    }

    @Test
    void actualSixteenMiBBoundaryAndNextByteUseValidSchema() throws Exception {
        List<ResourceFilterRule> rules = new ArrayList<>();
        for (int index = 0; index < 255; index++)
            rules.add(match(
                    index,
                    ResourceTypes.ITEM,
                    ResourceFilterRule.Selector.exact(id("a:" + "x".repeat(65533))),
                    ComponentCondition.idOnly()));
        rules.add(match(
                255, ResourceTypes.ITEM, ResourceFilterRule.Selector.exact(id("a:x")), ComponentCondition.idOnly()));
        var tag = ResourceFilterPresetNbt.encode(preset(rules));
        int remaining = ResourcePolicyNbt.MAX_BYTES - size(tag);
        assertTrue(remaining > 0 && remaining < 65532);
        var selector = tag.getList("rules", Tag.TAG_COMPOUND).getCompound(255).getCompound("selector");
        selector.putString("resource_id", "a:" + "x".repeat(1 + remaining));
        assertEquals(ResourcePolicyNbt.MAX_BYTES, size(tag));
        var restored = ResourceFilterPresetNbt.decode(tag);
        assertEquals(tag, ResourceFilterPresetNbt.encode(restored));
        selector.putString("resource_id", "a:" + "x".repeat(2 + remaining));
        assertEquals(ResourcePolicyNbt.MAX_BYTES + 1, size(tag));
        assertThrows(IllegalArgumentException.class, () -> ResourceFilterPresetNbt.decode(tag));
        var tooLargeRules = new ArrayList<>(restored.rules());
        tooLargeRules.set(
                255,
                match(
                        255,
                        ResourceTypes.ITEM,
                        ResourceFilterRule.Selector.exact(id(selector.getString("resource_id"))),
                        ComponentCondition.idOnly()));
        assertThrows(IllegalArgumentException.class, () -> ResourceFilterPresetNbt.encode(preset(tooLargeRules)));
    }

    @Test
    void collectionCapsAndNativeListTypesRejectBeforePublication() {
        CompoundTag tag = ResourceFilterPresetNbt.encode(preset(List.of()));
        tag.remove("items");
        ListTag entries = new ListTag();
        CompoundTag reference = new CompoundTag();
        reference.putString("kind", "reference");
        reference.putUUID("rule_id", ROOT);
        reference.putUUID("preset_id", ROOT);
        for (int index = 0; index <= ResourceFilterPreset.MAX_ENTRIES; index++) entries.add(reference);
        tag.put("rules", entries);
        assertThrows(IllegalArgumentException.class, () -> ResourceFilterPresetNbt.decode(tag));
        ListTag strings = new ListTag();
        strings.add(net.minecraft.nbt.StringTag.valueOf("wrong"));
        tag.put("rules", strings);
        assertThrows(IllegalArgumentException.class, () -> ResourceFilterPresetNbt.decode(tag));
        var selectedTag = ResourceFilterPresetNbt.encode(preset(List.of(
                match(1, ResourceTypes.ITEM, ResourceFilterRule.Selector.wholeType(), ComponentCondition.idOnly()))));
        var components = row(selectedTag).getCompound("components");
        components.putString("mode", "selected");
        ListTag selected = new ListTag();
        CompoundTag value = new CompoundTag();
        value.putString("component_id", "a:b");
        value.putByteArray("canonical", new byte[] {1, 1});
        for (int index = 0; index <= ResourceFilterPreset.MAX_ENTRIES; index++) selected.add(value);
        components.put("selected", selected);
        assertThrows(IllegalArgumentException.class, () -> ResourceFilterPresetNbt.decode(selectedTag));
    }

    @Test
    void compactMigrationKeepsEveryLegalM2ByteAtTheOldLimit() throws Exception {
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (int index = 0; index < 255; index++) {
            String prefix = "a:" + index + "/";
            ids.add(id(prefix + "x".repeat(65535 - prefix.length())));
        }
        var initial = new ItemFilterPreset(ROOT, new ManagedName("Test"), 7, ids);
        CompoundTag old = OwnerPresetNbt.encode(List.of(initial)).getCompound(0);
        int remaining = ItemFilterPreset.MAXIMUM_ENCODED_BYTES - (size(old) - 1);
        assertTrue(remaining > 4 && remaining < 65535);
        for (int extra : List.of(remaining - 1, remaining)) {
            Set<ResourceLocation> boundaryIds = new LinkedHashSet<>(ids);
            boundaryIds.add(id("b:" + "y".repeat(extra - 4)));
            var legacy = new ItemFilterPreset(ROOT, initial.name(), 7, boundaryIds);
            CompoundTag legacyTag = OwnerPresetNbt.encode(List.of(legacy)).getCompound(0);
            assertEquals(ItemFilterPreset.MAXIMUM_ENCODED_BYTES - (remaining - extra), size(legacyTag) - 1);
            var migrated = ResourceFilterPresetNbt.migrate(legacy);
            CompoundTag compact = ResourceFilterPresetNbt.encode(migrated);
            assertTrue(compact.contains("items", Tag.TAG_LIST));
            assertFalse(compact.contains("rules"));
            assertEquals(size(legacyTag) - 3, size(compact));
            assertTrue(size(compact) <= ItemFilterPreset.MAXIMUM_ENCODED_BYTES);
            var restored = ResourceFilterPresetNbt.decode(compact);
            assertEquals(ROOT, restored.id());
            assertEquals(initial.name(), restored.name());
            assertEquals(7, restored.revision());
            assertEquals(
                    boundaryIds,
                    restored.rules().stream()
                            .map(rule -> ((ResourceFilterRule.Exact) ((ResourceFilterRule.Match) rule).selector())
                                    .resourceId())
                            .collect(java.util.stream.Collectors.toSet()));
            assertEquals(compact, ResourceFilterPresetNbt.encode(restored));
            assertEquals(compact, ResourceFilterPresetNbt.encode(ResourceFilterPresetNbt.decodeLegacy(legacyTag)));
            if (extra == remaining) {
                ListTag items = compact.getList("items", Tag.TAG_STRING);
                String last = items.getString(items.size() - 1);
                items.set(items.size() - 1, net.minecraft.nbt.StringTag.valueOf(last + "xx"));
                assertEquals(ItemFilterPreset.MAXIMUM_ENCODED_BYTES, size(compact));
                assertEquals(compact, ResourceFilterPresetNbt.encode(ResourceFilterPresetNbt.decode(compact)));
                items.set(items.size() - 1, net.minecraft.nbt.StringTag.valueOf(last + "xxx"));
                assertEquals(ItemFilterPreset.MAXIMUM_ENCODED_BYTES + 1, size(compact));
                assertThrows(IllegalArgumentException.class, () -> ResourceFilterPresetNbt.decode(compact));
            }
        }
    }

    @Test
    void compactRuleIdentitySurvivesOrderAndUnrelatedDeletion() {
        var first = id("a:first");
        var second = id("a:second");
        var last = id("a:last");
        var all = ResourceFilterPresetNbt.migrate(new ItemFilterPreset(
                ROOT, new ManagedName("Test"), 7, new LinkedHashSet<>(List.of(second, first, last))));
        var fewer = ResourceFilterPresetNbt.migrate(
                new ItemFilterPreset(ROOT, all.name(), 8, new LinkedHashSet<>(List.of(second, last))));
        for (var rule : fewer.rules()) {
            var exact = ((ResourceFilterRule.Exact) ((ResourceFilterRule.Match) rule).selector()).resourceId();
            var retained = all.rules().stream()
                    .filter(value -> ((ResourceFilterRule.Exact) ((ResourceFilterRule.Match) value).selector())
                            .resourceId()
                            .equals(exact))
                    .findFirst()
                    .orElseThrow();
            assertEquals(retained.id(), rule.id());
        }
        var reversed = new ArrayList<>(all.rules());
        java.util.Collections.reverse(reversed);
        assertEquals(
                ResourceFilterPresetNbt.encode(all),
                ResourceFilterPresetNbt.encode(new ResourceFilterPreset(ROOT, all.name(), 7, reversed)));
    }

    @Test
    void compactDiscriminatesStrictlyAndGeneralContentNeverLosesIdentity() {
        var migrated = ResourceFilterPresetNbt.migrate(
                new ItemFilterPreset(ROOT, new ManagedName("Test"), 7, Set.of(id("minecraft:stone"))));
        assertEquals(
                UUID.fromString("954ffa3e-b1d7-4f1f-c5e4-84fbee4e6b53"),
                migrated.rules().getFirst().id());
        CompoundTag compact = ResourceFilterPresetNbt.encode(migrated);
        assertEquals(Set.of("preset_id", "name", "revision", "items"), compact.getAllKeys());
        List<java.util.function.Consumer<CompoundTag>> mutations = List.of(
                tag -> tag.put("rules", new ListTag()),
                tag -> tag.putString("format", "compact"),
                tag -> tag.putString("items", "minecraft:stone"),
                tag -> tag.getList("items", Tag.TAG_STRING).add(net.minecraft.nbt.StringTag.valueOf("minecraft:stone")),
                tag -> {
                    ListTag items = new ListTag();
                    items.add(net.minecraft.nbt.StringTag.valueOf("stone"));
                    tag.put("items", items);
                },
                tag -> {
                    ListTag items = new ListTag();
                    items.add(new CompoundTag());
                    tag.put("items", items);
                });
        for (var mutation : mutations) {
            var bad = compact.copy();
            mutation.accept(bad);
            assertThrows(IllegalArgumentException.class, () -> ResourceFilterPresetNbt.decode(bad));
        }
        ListTag tooMany = new ListTag();
        for (int index = 0; index <= ResourceFilterPreset.MAX_ENTRIES; index++)
            tooMany.add(net.minecraft.nbt.StringTag.valueOf("a:" + index));
        var badCount = compact.copy();
        badCount.put("items", tooMany);
        assertThrows(IllegalArgumentException.class, () -> ResourceFilterPresetNbt.decode(badCount));
        var original = (ResourceFilterRule.Match) migrated.rules().getFirst();
        var sample = ItemVariant.from(new ItemStack(Items.STONE), PROVIDER);
        List<ResourceFilterRule> general = List.of(
                match(984, ResourceTypes.ITEM, original.selector(), ComponentCondition.idOnly()),
                new ResourceFilterRule.Match(
                        original.id(), ResourceTypes.ITEM, original.selector(), ComponentCondition.full(sample)),
                new ResourceFilterRule.Match(
                        original.id(),
                        ResourceTypes.ITEM,
                        original.selector(),
                        ComponentCondition.selected(sample, Set.of())),
                new ResourceFilterRule.Match(
                        original.id(), ResourceTypes.FLUID, original.selector(), ComponentCondition.idOnly()),
                new ResourceFilterRule.Reference(original.id(), new UUID(1, 99)));
        for (var rule : general) {
            var encoded = ResourceFilterPresetNbt.encode(preset(List.of(rule)));
            assertTrue(encoded.contains("rules", Tag.TAG_LIST));
            assertFalse(encoded.contains("items"));
            var restored = ResourceFilterPresetNbt.decode(encoded);
            assertEquals(rule.id(), restored.rules().getFirst().id());
            assertEquals(encoded, ResourceFilterPresetNbt.encode(restored));
        }
    }

    private static int size(CompoundTag tag) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(Tag.TAG_COMPOUND);
            tag.write(output);
        }
        return bytes.size();
    }
}
