// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.filter.ItemFilterPreset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** Strict owner-library codec; malformed collections reject before publication and caller tags are never modified. */
final class OwnerPresetNbt {
    private OwnerPresetNbt() {}

    static Map<UUID, ItemFilterPreset> decode(CompoundTag root) {
        ListTag entries = readList(root, "filter_presets", Tag.TAG_COMPOUND);
        Map<UUID, ItemFilterPreset> result = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            if (!entry.getAllKeys().equals(Set.of("preset_id", "name", "revision", "item_ids")))
                throw new IllegalArgumentException("Invalid preset fields");
            UUID id = ManagedDataNbt.readUuid(entry, "preset_id");
            var name = ManagedDataNbt.readName(entry);
            ManagedDataNbt.requireType(entry, "revision", Tag.TAG_LONG);
            ListTag rules = readList(entry, "item_ids", Tag.TAG_STRING);
            long encodedBytes = 77;
            for (int character = 0; character < name.value().length(); character++) {
                char value = name.value().charAt(character);
                encodedBytes += value >= 1 && value <= 127 ? 1 : value <= 2047 ? 2 : 3;
            }
            // Preflight the whole external object before allocating decoded rule identities or a rule set.
            for (int rule = 0; rule < rules.size(); rule++) {
                String value = rules.getString(rule);
                if (value.length() > ItemFilterPreset.MAXIMUM_ITEM_ID_BYTES)
                    throw new IllegalArgumentException("Exact ID exceeds native UTF limit");
                encodedBytes += 2L + value.length();
                if (encodedBytes > ItemFilterPreset.MAXIMUM_ENCODED_BYTES)
                    throw new IllegalArgumentException("Preset exceeds managed object byte limit");
            }
            Set<ResourceLocation> ids = new HashSet<>();
            for (int rule = 0; rule < rules.size(); rule++) {
                String value = rules.getString(rule);
                if (value.length() > ItemFilterPreset.MAXIMUM_ITEM_ID_BYTES)
                    throw new IllegalArgumentException("Exact ID exceeds native UTF limit");
                ResourceLocation itemId = ResourceLocation.parse(value);
                if (!itemId.toString().equals(value) || !ids.add(itemId))
                    throw new IllegalArgumentException("Noncanonical or duplicate exact item ID");
            }
            ItemFilterPreset preset = new ItemFilterPreset(id, name, entry.getLong("revision"), ids);
            if (result.putIfAbsent(id, preset) != null || !names.add(name.uniquenessKey()))
                throw new IllegalArgumentException("Duplicate preset ID or name");
        }
        return result;
    }

    static ListTag encode(Collection<ItemFilterPreset> values) {
        var sorted = new ArrayList<>(values);
        sorted.sort(Comparator.comparing(ItemFilterPreset::id));
        ListTag entries = new ListTag();
        for (ItemFilterPreset preset : sorted) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("preset_id", preset.id());
            entry.putString("name", preset.name().value());
            entry.putLong("revision", preset.revision());
            var ids = new ArrayList<>(preset.itemIds());
            ids.sort(Comparator.comparing(ResourceLocation::toString));
            ListTag rules = new ListTag();
            for (ResourceLocation id : ids) rules.add(StringTag.valueOf(id.toString()));
            entry.put("item_ids", rules);
            entries.add(entry);
        }
        return entries;
    }

    private static ListTag readList(CompoundTag root, String key, int type) {
        ManagedDataNbt.requireType(root, key, Tag.TAG_LIST);
        ListTag list = (ListTag) root.get(key);
        if (list.size() > 262144
                || (list.getElementType() != type && !(list.isEmpty() && list.getElementType() == Tag.TAG_END)))
            throw new IllegalArgumentException("Invalid preset collection");
        return list;
    }
}
