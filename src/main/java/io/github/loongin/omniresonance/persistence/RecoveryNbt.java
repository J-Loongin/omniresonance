// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

final class RecoveryNbt {
    private RecoveryNbt() {}

    static Map<ResourceVariantKey, Long> decode(CompoundTag root) {
        ManagedDataNbt.requireType(root, "recovery", Tag.TAG_LIST);
        ListTag entries = (ListTag) root.get("recovery");
        if (entries.size() > 262144
                || (entries.getElementType() != Tag.TAG_COMPOUND
                        && !(entries.isEmpty() && entries.getElementType() == Tag.TAG_END)))
            throw new IllegalArgumentException("Invalid recovery collection");
        Map<ResourceVariantKey, Long> values = new HashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            if (!entry.getAllKeys().equals(java.util.Set.of("type_id", "canonical_bytes", "amount")))
                throw new IllegalArgumentException("Invalid recovery fields");
            ManagedDataNbt.requireType(entry, "type_id", Tag.TAG_STRING);
            ManagedDataNbt.requireType(entry, "canonical_bytes", Tag.TAG_BYTE_ARRAY);
            ManagedDataNbt.requireType(entry, "amount", Tag.TAG_LONG);
            String type = entry.getString("type_id");
            if (type.length() > 128) throw new IllegalArgumentException("Resource type ID exceeds limit");
            ResourceLocation typeId = ResourceLocation.parse(type);
            if (!typeId.toString().equals(type)) throw new IllegalArgumentException("Noncanonical resource type ID");
            ResourceVariantKey key = new ResourceVariantKey(typeId, entry.getByteArray("canonical_bytes"));
            long amount = entry.getLong("amount");
            if (amount < 0 || values.putIfAbsent(key, amount) != null)
                throw new IllegalArgumentException("Negative or duplicate recovery entry");
        }
        return values;
    }

    static ListTag encode(Map<ResourceVariantKey, Long> values) {
        ListTag entries = new ListTag();
        for (var value : values.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("type_id", value.getKey().typeId().toString());
            entry.putByteArray("canonical_bytes", value.getKey().canonicalBytes());
            entry.putLong("amount", value.getValue());
            entries.add(entry);
        }
        return entries;
    }
}
