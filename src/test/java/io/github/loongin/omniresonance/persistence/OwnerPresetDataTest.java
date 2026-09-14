// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.filter.ItemFilterPreset;
import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class OwnerPresetDataTest {
    static final UUID OWNER = new UUID(1, 1), ID = new UUID(2, 2);

    static ItemFilterPreset preset(UUID id, String name, long revision, Set<ResourceLocation> ids) {
        return new ItemFilterPreset(id, new ManagedName(name), revision, ids);
    }

    @Test
    void loweredRuleQuotaAllowsAtomicReplacementButRejectsGrowth() {
        OwnerSavedData data = OwnerSavedData.create(OWNER, null);
        var stone = ResourceLocation.parse("minecraft:stone");
        var iron = ResourceLocation.parse("minecraft:iron_ingot");
        data.putPreset(preset(ID, "Ores", 0, Set.of(stone)), 0, -1, -1);
        var replacement = preset(ID, "Ores", 1, Set.of(iron));
        data.putPreset(replacement, 1, 0, 0);
        assertEquals(
                ResourceFilterPresetNbt.encode(ResourceFilterPresetNbt.migrate(replacement)),
                ResourceFilterPresetNbt.encode(data.findPreset(ID).orElseThrow()));
        assertEquals(2, data.presetLibraryRevision());
        data.setDirty(false);
        assertThrows(
                IllegalArgumentException.class,
                () -> data.putPreset(preset(ID, "Ores", 2, Set.of(stone, iron)), 2, 0, 0));
        assertFalse(data.isDirty());
        assertEquals(
                ResourceFilterPresetNbt.encode(ResourceFilterPresetNbt.migrate(replacement)),
                ResourceFilterPresetNbt.encode(data.findPreset(ID).orElseThrow()));
        assertEquals(2, data.presetLibraryRevision());
    }

    @Test
    void createUpdateDeleteAreRevisionCheckedAndRoundTrip() {
        OwnerSavedData data = OwnerSavedData.create(OWNER, null);
        data.setDirty(false);
        ItemFilterPreset first = preset(ID, "Stone", 0, Set.of(ResourceLocation.parse("minecraft:stone")));
        data.putPreset(first, 0, 512, 1024);
        assertEquals(1, data.presetLibraryRevision());
        assertEquals(
                ResourceFilterPresetNbt.encode(ResourceFilterPresetNbt.migrate(first)),
                ResourceFilterPresetNbt.encode(data.findPreset(ID).orElseThrow()));
        assertThrows(IllegalStateException.class, () -> data.putPreset(first, 0, 512, 1024));
        ItemFilterPreset updated = preset(ID, "Rock", 1, Set.of());
        data.putPreset(updated, 1, 512, 1024);
        CompoundTag saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        OwnerSavedData restored = OwnerSavedData.load(OWNER, saved);
        assertEquals(
                ResourceFilterPresetNbt.encode(ResourceFilterPresetNbt.migrate(updated)),
                ResourceFilterPresetNbt.encode(restored.findPreset(ID).orElseThrow()));
        assertFalse(restored.isDirty());
        assertEquals(saved, restored.save(new CompoundTag(), RegistryAccess.EMPTY));
        restored.removePreset(ID, 2);
        assertEquals(3, restored.presetLibraryRevision());
        assertEquals(0, restored.presets().size());
    }

    @Test
    void duplicateNameInvalidRevisionAndLoweredQuotaAreAtomic() {
        OwnerSavedData data = OwnerSavedData.create(OWNER, null);
        data.putPreset(preset(ID, "Stone", 0, Set.of(ResourceLocation.parse("minecraft:stone"))), 0, 512, 1024);
        data.setDirty(false);
        assertThrows(
                IllegalArgumentException.class,
                () -> data.putPreset(preset(new UUID(2, 3), "STONE", 0, Set.of()), 1, 512, 1024));
        assertThrows(IllegalStateException.class, () -> data.putPreset(preset(ID, "Other", 0, Set.of()), 1, 512, 1024));
        assertThrows(
                IllegalArgumentException.class,
                () -> data.putPreset(preset(new UUID(2, 3), "New", 0, Set.of()), 1, 0, 0));
        assertFalse(data.isDirty());
        assertEquals(1, data.presetLibraryRevision());
        data.putPreset(preset(ID, "Renamed", 1, Set.of(ResourceLocation.parse("minecraft:stone"))), 1, 0, 0);
        data.putPreset(preset(ID, "Reduced", 2, Set.of()), 2, 0, 0);
        assertEquals(0, data.findPreset(ID).orElseThrow().rules().size());
    }

    @Test
    void exhaustedLibraryRevisionFailsBeforeMutation() {
        CompoundTag tag = OwnerSavedData.create(OWNER, null).save(new CompoundTag(), RegistryAccess.EMPTY);
        tag.putLong("preset_library_revision", Long.MAX_VALUE);
        OwnerSavedData data = OwnerSavedData.load(OWNER, tag);
        assertThrows(
                IllegalStateException.class,
                () -> data.putPreset(preset(ID, "New", 0, Set.of()), Long.MAX_VALUE, 512, 1024));
        assertFalse(data.isDirty());
        assertEquals(0, data.presets().size());
    }

    @Test
    void duplicatePersistedPresetIdsNamesAndRulesRejectEntireShard() {
        OwnerSavedData data = OwnerSavedData.create(OWNER, null);
        data.putPreset(preset(ID, "Stone", 0, Set.of(ResourceLocation.parse("minecraft:stone"))), 0, 512, 1024);
        CompoundTag tag = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        ListTag entries = tag.getList("filter_presets", Tag.TAG_COMPOUND);
        entries.add(entries.getCompound(0).copy());
        CompoundTag before = tag.copy();
        assertThrows(IllegalArgumentException.class, () -> OwnerSavedData.load(OWNER, tag));
        assertEquals(before, tag);
        entries.getCompound(1).putUUID("preset_id", new UUID(2, 3));
        assertThrows(IllegalArgumentException.class, () -> OwnerSavedData.load(OWNER, tag));
        entries.remove(1);
        ListTag rules = entries.getCompound(0).getList("items", Tag.TAG_STRING);
        rules.add(rules.get(0).copy());
        assertThrows(IllegalArgumentException.class, () -> OwnerSavedData.load(OWNER, tag));
    }
}
