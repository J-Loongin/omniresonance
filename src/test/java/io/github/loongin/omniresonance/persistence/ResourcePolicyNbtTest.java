// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.ItemTransferPolicy;
import io.github.loongin.omniresonance.transfer.RedstoneCondition;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.StoredResourcePolicy;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ResourcePolicyNbtTest {
    static final ResourceLocation MISSING = ResourceLocation.parse("example:missing");
    static final Set<ResourceLocation> REGISTERED =
            Set.of(ResourceTypes.ITEM, ResourceTypes.FLUID, ResourceTypes.ENERGY);

    static CompoundTag input() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("interval_ticks", 7);
        tag.putString("redstone_condition", "signal");
        tag.putString("filter_mode", "blacklist");
        tag.putUUID("filter_preset_id", new UUID(1, 2));
        tag.putLong("keep_count", Long.MAX_VALUE);
        CompoundTag scope = new CompoundTag();
        scope.putString("kind", "all");
        tag.put("resource_scope", scope);
        tag.put("resource_policy_overrides", new ListTag());
        return tag;
    }

    static CompoundTag row(String id, Integer rate, String mode, Long batch) {
        CompoundTag row = new CompoundTag();
        row.putString("type_id", id);
        if (rate != null) row.putInt("rate", rate);
        if (mode != null) row.putString("batch_mode", mode);
        if (batch != null) row.putLong("batch_size", batch);
        return row;
    }

    static void add(CompoundTag tag, CompoundTag row) {
        tag.getList("resource_policy_overrides", Tag.TAG_COMPOUND).add(row);
    }

    static StoredResourcePolicy decode(CompoundTag tag) {
        return ResourcePolicyNbt.decode(tag, TransferDirection.INPUT, REGISTERED);
    }

    @Test
    void longRatesAndLegacyIntRatesRoundTripWithoutNarrowing() {
        var legacy = input();
        add(legacy, row("minecraft:item", 123, null, null));
        var original = legacy.copy();
        var migrated = decode(legacy);
        assertEquals(123L, migrated.effectivePolicy().rate(ResourceTypes.ITEM));
        assertEquals(original, legacy);
        var full = input();
        var entry = row("minecraft:item", null, null, null);
        entry.putLong("rate", Long.MAX_VALUE);
        add(full, entry);
        var decoded = decode(full);
        assertEquals(Long.MAX_VALUE, decoded.effectivePolicy().rate(ResourceTypes.ITEM));
        assertEquals(decoded, decode(ResourcePolicyNbt.encode(decoded)));
        var opaque = ResourcePolicyNbt.decode(full, TransferDirection.INPUT, Set.of());
        assertEquals(
                Long.MAX_VALUE,
                opaque.missingTypeOverrides().get(ResourceTypes.ITEM).rate());
        assertEquals(decoded, decode(ResourcePolicyNbt.encode(opaque)));
        entry.putLong("rate", -1);
        assertThrows(IllegalArgumentException.class, () -> decode(full));
    }

    @Test
    void inputRoundTripRetainsAllFieldsAndLargeExactBatch() {
        CompoundTag tag = input();
        add(tag, row("minecraft:item", 3, "exact", Long.MAX_VALUE));
        StoredResourcePolicy stored = decode(tag);
        var policy = (ResourceTransferPolicy.Input) stored.effectivePolicy();
        assertEquals(7, policy.intervalTicks());
        assertEquals(Long.MAX_VALUE, policy.keepCount());
        assertEquals(new UUID(1, 2), policy.filterPresetId());
        assertEquals(FilterMode.BLACKLIST, policy.filterMode());
        assertEquals(RedstoneCondition.SIGNAL, policy.redstoneCondition());
        assertEquals(
                new ResourceTransferPolicy.InputOverride(3, ResourceTransferPolicy.BatchMode.EXACT, Long.MAX_VALUE),
                policy.resourcePolicyOverrides().get(ResourceTypes.ITEM));
        assertEquals(tag, ResourcePolicyNbt.encode(stored));
    }

    @Test
    void outputAndCustomScopeRoundTrip() {
        var policy = new ResourceTransferPolicy.Output(
                Integer.MAX_VALUE,
                ResourceScope.customSet(Set.of(ResourceTypes.FLUID)),
                RedstoneCondition.NO_SIGNAL,
                null,
                FilterMode.WHITELIST,
                Map.of(ResourceTypes.FLUID, new ResourceTransferPolicy.OutputOverride(1)),
                Integer.MIN_VALUE);
        var stored = new StoredResourcePolicy(policy, Map.of());
        CompoundTag tag = ResourcePolicyNbt.encode(stored);
        assertFalse(tag.contains("keep_count"));
        assertEquals(
                policy,
                ResourcePolicyNbt.decode(tag, TransferDirection.OUTPUT, REGISTERED)
                        .effectivePolicy());
        assertEquals(Set.of("kind", "types"), tag.getCompound("resource_scope").getAllKeys());
    }

    @Test
    void knownDefaultsNormalizeButMissingExplicitDefaultsRemainLossless() {
        CompoundTag tag = input();
        add(tag, row("minecraft:item", Integer.MAX_VALUE, "greedy", 99L));
        CompoundTag raw = row("example:missing", Integer.MAX_VALUE, "greedy", 99L);
        add(tag, raw);
        var stored = decode(tag);
        assertTrue(stored.effectivePolicy().resourcePolicyOverrides().isEmpty());
        assertEquals(
                raw,
                ResourcePolicyNbt.encode(stored)
                        .getList("resource_policy_overrides", Tag.TAG_COMPOUND)
                        .getCompound(0));
        CompoundTag encoded = ResourcePolicyNbt.encode(stored);
        assertEquals(Set.of("kind"), encoded.getCompound("resource_scope").getAllKeys());
        assertTrue(ResourcePolicyNbt.decode(encoded, TransferDirection.INPUT, Set.of(MISSING))
                .missingTypeOverrides()
                .isEmpty());
    }

    @Test
    void adapterRestorationAndDirectionRoundTripPreserveRatesWithoutRevivingBatch() {
        CompoundTag tag = input();
        add(tag, row("example:missing", 13, "exact", 27L));
        var stored = decode(tag);
        var restored =
                ResourcePolicyNbt.decode(ResourcePolicyNbt.encode(stored), TransferDirection.INPUT, Set.of(MISSING));
        assertEquals(
                new ResourceTransferPolicy.InputOverride(13, ResourceTransferPolicy.BatchMode.EXACT, 27),
                restored.effectivePolicy().resourcePolicyOverrides().get(MISSING));
        var switched = stored.switchDirection(TransferDirection.OUTPUT).switchDirection(TransferDirection.INPUT);
        CompoundTag encoded = ResourcePolicyNbt.encode(switched);
        assertEquals(0, encoded.getLong("keep_count"));
        assertEquals(
                row("example:missing", 13, null, null),
                encoded.getList("resource_policy_overrides", Tag.TAG_COMPOUND).getCompound(0));
        assertEquals(tag, ResourcePolicyNbt.encode(stored));
    }

    @Test
    void tagsAndMissingMapsAreDetached() {
        CompoundTag tag = input();
        add(tag, row("example:missing", 13, "exact", 27L));
        CompoundTag original = tag.copy();
        var stored = decode(tag);
        tag.getList("resource_policy_overrides", Tag.TAG_COMPOUND)
                .getCompound(0)
                .putInt("rate", 9);
        CompoundTag encoded = ResourcePolicyNbt.encode(stored);
        encoded.putLong("keep_count", 0);
        assertEquals(original, ResourcePolicyNbt.encode(stored));
        assertThrows(
                UnsupportedOperationException.class,
                () -> stored.missingTypeOverrides().clear());
    }

    @Test
    void legacyConversionRetainsEveryOldParameterAndItemOnlyScope() {
        for (ItemTransferPolicy old : new ItemTransferPolicy[] {
            new ItemTransferPolicy.Input(4, 32, RedstoneCondition.SIGNAL, new UUID(6, 7), FilterMode.BLACKLIST, 19),
            new ItemTransferPolicy.Output(8, 77, RedstoneCondition.NO_SIGNAL, new UUID(8, 9), FilterMode.BLACKLIST, -12)
        }) {
            CompoundTag tag = ItemPolicyNbt.encode(old);
            CompoundTag before = tag.copy();
            var converted = ResourcePolicyNbt.decodeLegacyItemPolicy(tag, old.direction());
            assertEquals(ResourceTransferPolicy.legacy(old), converted.effectivePolicy());
            assertEquals(
                    ResourceScope.customSet(Set.of(ResourceTypes.ITEM)),
                    converted.effectivePolicy().scope());
            assertEquals(before, tag);
            tag.putInt("unexpected", 1);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ResourcePolicyNbt.decodeLegacyItemPolicy(tag, old.direction()));
        }
    }

    @Test
    void rejectsMalformedPolicyWithoutChangingInput() {
        for (Consumer<CompoundTag> corrupt : java.util.List.<Consumer<CompoundTag>>of(
                t -> t.putInt("priority", 0),
                t -> t.remove("keep_count"),
                t -> t.putInt("keep_count", 0),
                t -> t.putLong("interval_ticks", 1),
                t -> t.putInt("interval_ticks", 0),
                t -> t.putLong("keep_count", -1),
                t -> t.putString("filter_mode", "WHITELIST"),
                t -> t.putString("redstone_condition", "invalid"),
                t -> t.putIntArray("filter_preset_id", new int[] {1, 2}),
                t -> t.putString("resource_scope", "all"),
                t -> t.getCompound("resource_scope").put("types", new ListTag()),
                t -> t.getCompound("resource_scope").putString("kind", "ALL"),
                t -> t.putString("resource_policy_overrides", "invalid"),
                t -> {
                    ListTag rows = new ListTag();
                    rows.add(StringTag.valueOf("bad"));
                    t.put("resource_policy_overrides", rows);
                },
                t -> {
                    ListTag ids = new ListTag();
                    ids.add(net.minecraft.nbt.IntTag.valueOf(1));
                    t.getCompound("resource_scope").putString("kind", "custom_set");
                    t.getCompound("resource_scope").put("types", ids);
                },
                t -> add(t, row("item", 1, null, null)),
                t -> add(t, row("Minecraft:item", 1, null, null)),
                t -> add(t, row("x:" + "a".repeat(127), 1, null, null)),
                t -> add(t, row("minecraft:item", 0, null, null)),
                t -> add(t, row("example:missing", -1, null, null)),
                t -> add(t, row("minecraft:item", 1, "exact", 0L)),
                t -> add(t, row("example:missing", 1, "exact", -1L)),
                t -> add(t, row("example:missing", 1, "exact", null)),
                t -> add(t, row("example:missing", 1, null, 4L)),
                t -> add(t, row("example:missing", 1, "unknown", 4L)),
                t -> {
                    CompoundTag r = row("minecraft:item", 1, null, null);
                    r.putDouble("rate", 1);
                    add(t, r);
                },
                t -> {
                    CompoundTag r = row("minecraft:item", 1, "exact", 1L);
                    r.putInt("batch_size", 1);
                    add(t, r);
                },
                t -> {
                    CompoundTag r = row("example:missing", 1, null, null);
                    r.putInt("extra", 1);
                    add(t, r);
                },
                t -> {
                    add(t, row("minecraft:item", 1, null, null));
                    add(t, row("minecraft:item", 2, null, null));
                })) {
            CompoundTag tag = input();
            corrupt.accept(tag);
            CompoundTag before = tag.copy();
            assertThrows(IllegalArgumentException.class, () -> decode(tag));
            assertEquals(before, tag);
        }
    }

    @Test
    void rejectsEmptyDuplicateAndOutOfScopeCustomSelectionsAndOutputBatch() {
        CompoundTag tag = input();
        CompoundTag scope = tag.getCompound("resource_scope");
        scope.putString("kind", "custom_set");
        ListTag types = new ListTag();
        scope.put("types", types);
        assertThrows(IllegalArgumentException.class, () -> decode(tag));
        types.add(StringTag.valueOf("minecraft:item"));
        types.add(StringTag.valueOf("minecraft:item"));
        assertThrows(IllegalArgumentException.class, () -> decode(tag));
        types.remove(1);
        add(tag, row("example:missing", 1, null, null));
        assertThrows(IllegalArgumentException.class, () -> decode(tag));
        CompoundTag output = input();
        output.remove("keep_count");
        output.putInt("priority", 0);
        add(output, row("example:missing", 1, "greedy", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ResourcePolicyNbt.decode(output, TransferDirection.OUTPUT, REGISTERED));
    }

    @Test
    void acceptsExactlySixteenMiBAndRejectsOneMoreActualNbtByte() throws java.io.IOException {
        CompoundTag tag = input();
        int base = binarySize(tag);
        CompoundTag sample = row("x:" + "a".repeat(126), null, null, null);
        int rowBytes = binarySize(sample) - 1;
        int rows = (16 * 1024 * 1024 - base) / rowBytes;
        ListTag overrides = tag.getList("resource_policy_overrides", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows; i++) {
            String suffix = Integer.toString(i, 36);
            overrides.add(row("x:" + "0".repeat(126 - suffix.length()) + suffix, null, null, null));
        }
        int remaining = 16 * 1024 * 1024 - binarySize(tag);
        if (remaining < 17) {
            overrides.remove(overrides.size() - 1);
            remaining += rowBytes;
        }
        overrides.add(row("z:" + "b".repeat(remaining - 15), null, null, null));
        assertEquals(16 * 1024 * 1024, binarySize(tag));
        var stored = ResourcePolicyNbt.decode(tag, TransferDirection.INPUT, Set.of());
        assertEquals(16 * 1024 * 1024, binarySize(ResourcePolicyNbt.encode(stored)));
        CompoundTag last = overrides.getCompound(overrides.size() - 1);
        last.putString("type_id", last.getString("type_id") + "c");
        assertEquals(16 * 1024 * 1024 + 1, binarySize(tag));
        assertThrows(IllegalArgumentException.class, () -> decode(tag));
        Map<ResourceLocation, StoredResourcePolicy.RawOverride> larger =
                new java.util.HashMap<>(stored.missingTypeOverrides());
        ResourceLocation key = ResourceLocation.parse(
                last.getString("type_id").substring(0, last.getString("type_id").length() - 1));
        larger.put(ResourceLocation.parse(last.getString("type_id")), larger.remove(key));
        assertThrows(
                IllegalArgumentException.class,
                () -> ResourcePolicyNbt.encode(new StoredResourcePolicy(stored.effectivePolicy(), larger)));
    }

    @Test
    void acceptsCountBoundaryAndRejectsOneAdditionalUniqueEntry() {
        CompoundTag tag = input();
        ListTag list = tag.getList("resource_policy_overrides", Tag.TAG_COMPOUND);
        for (int i = 0; i < ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS; i++) list.add(row("x:" + i, null, null, null));
        assertEquals(
                ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS,
                decode(tag).missingTypeOverrides().size());
        list.add(row("x:extra", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> decode(tag));
        CompoundTag scope = new CompoundTag();
        scope.putString("kind", "custom_set");
        ListTag types = new ListTag();
        for (int i = 0; i < ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS; i++) types.add(StringTag.valueOf("x:" + i));
        scope.put("types", types);
        tag.put("resource_scope", scope);
        tag.put("resource_policy_overrides", new ListTag());
        assertEquals(
                ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS,
                decode(tag).effectivePolicy().scope().resourceTypeIds().size());
        types.add(StringTag.valueOf("x:extra"));
        assertThrows(IllegalArgumentException.class, () -> decode(tag));
    }

    private static int binarySize(CompoundTag tag) throws java.io.IOException {
        try (java.io.DataOutputStream output = new java.io.DataOutputStream(java.io.OutputStream.nullOutputStream())) {
            output.writeByte(Tag.TAG_COMPOUND);
            tag.write(output);
            return output.size();
        }
    }
}
