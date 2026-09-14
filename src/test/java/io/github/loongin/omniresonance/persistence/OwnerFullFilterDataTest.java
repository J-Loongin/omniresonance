// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class OwnerFullFilterDataTest {
    @Test
    void newOwnerUsesFullResourceSchema() {
        var owner = OwnerSavedData.create(new UUID(1, 2), null);
        assertEquals(3, owner.save(new CompoundTag(), RegistryAccess.EMPTY).getInt("schema_version"));
    }

    @Test
    void literalV2MigrationAndFullV3ReloadPreserveIdentityRevisionsAndSource() {
        UUID id = new UUID(4, 5), ownerId = new UUID(1, 2), network = new UUID(6, 7);
        CompoundTag old = new CompoundTag();
        old.putInt("schema_version", 2);
        old.putUUID("owner_id", ownerId);
        old.putUUID("default_network_id", network);
        old.putLong("preset_library_revision", 17);
        var entries = new net.minecraft.nbt.ListTag();
        var preset = new CompoundTag();
        preset.putUUID("preset_id", id);
        preset.putString("name", "Legacy");
        preset.putLong("revision", 9);
        var ids = new net.minecraft.nbt.ListTag();
        ids.add(net.minecraft.nbt.StringTag.valueOf("minecraft:stone"));
        preset.put("item_ids", ids);
        entries.add(preset);
        old.put("filter_presets", entries);
        CompoundTag original = old.copy();
        var restored = OwnerSavedData.load(ownerId, old);
        assertEquals(original, old);
        org.junit.jupiter.api.Assertions.assertFalse(restored.isDirty());
        assertEquals(17, restored.presetLibraryRevision());
        assertEquals(network, restored.defaultNetworkId().orElseThrow());
        assertEquals(9, restored.findPreset(id).orElseThrow().revision());
        var full = new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                id,
                new io.github.loongin.omniresonance.network.ManagedName("Legacy"),
                10,
                java.util.List.of(new io.github.loongin.omniresonance.filter.ResourceFilterRule.Reference(
                        new UUID(8, 9), new UUID(10, 11))));
        restored.putPreset(full, 17, -1, -1);
        var tag = restored.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertEquals(3, tag.getInt("schema_version"));
        var loaded = OwnerSavedData.load(ownerId, tag);
        assertEquals(full, loaded.findPreset(id).orElseThrow());
        org.junit.jupiter.api.Assertions.assertFalse(loaded.isDirty());
    }

    @Test
    void compactCopyRekeysOnlyDerivedLeavesWithoutExpandingNearLimitPreset() {
        UUID id = new UUID(4, 5), copiedId = new UUID(7, 8);
        java.util.Set<net.minecraft.resources.ResourceLocation> ids = new java.util.HashSet<>();
        for (int i = 0; i < 256; i++)
            ids.add(net.minecraft.resources.ResourceLocation.parse("test:" + i + "x".repeat(65520)));
        var original = ResourceFilterPresetNbt.migrate(new io.github.loongin.omniresonance.filter.ItemFilterPreset(
                id, new io.github.loongin.omniresonance.network.ManagedName("Near limit"), 4, ids));
        var copy = ResourceFilterPresetNbt.copy(
                original, copiedId, new io.github.loongin.omniresonance.network.ManagedName("Copied"));
        assertEquals(original.rules().size(), copy.rules().size());
        org.junit.jupiter.api.Assertions.assertTrue(
                ResourceFilterPresetNbt.encode(copy).contains("items"));
        for (var rule : copy.rules()) {
            var exact = (io.github.loongin.omniresonance.filter.ResourceFilterRule.Exact)
                    ((io.github.loongin.omniresonance.filter.ResourceFilterRule.Match) rule).selector();
            assertEquals(ResourceFilterPresetNbt.compactRuleId(copiedId, exact.resourceId()), rule.id());
        }
        var owner = OwnerSavedData.create(new UUID(1, 2), null);
        owner.putPreset(copy, 0, -1, -1);
        owner.setDirty(false);
        java.util.List<io.github.loongin.omniresonance.filter.ResourceFilterRule> expanded =
                new java.util.ArrayList<>();
        for (var rule : copy.rules()) {
            var match = (io.github.loongin.omniresonance.filter.ResourceFilterRule.Match) rule;
            expanded.add(new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                    new UUID(99, expanded.size()), match.resourceTypeId(), match.selector(), match.components()));
        }
        var oversized =
                new io.github.loongin.omniresonance.filter.ResourceFilterPreset(copiedId, copy.name(), 1, expanded);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> owner.putPreset(oversized, 1, -1, -1));
        org.junit.jupiter.api.Assertions.assertFalse(owner.isDirty());
        assertEquals(1, owner.presetLibraryRevision());
        assertEquals(copy, owner.findPreset(copiedId).orElseThrow());
    }
}
