// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer.fixtures;

import io.github.loongin.omniresonance.transfer.CanonicalResourceNbt;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Detached legacy fixtures: reverse serialized cure sets without changing any member or other component. */
public final class LegacyEffectKeys {
    private LegacyEffectKeys() {}

    public static ResourceVariantKey reverse(ResourceVariantKey key) {
        Tag tag = CanonicalResourceNbt.decode(key.canonicalBytes());
        reverse(tag);
        return new ResourceVariantKey(key.typeId(), CanonicalResourceNbt.encode(tag));
    }

    private static void reverse(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            for (String name : compound.getAllKeys()) {
                Tag child = compound.get(name);
                if (name.equals("neoforge:cures") && child instanceof ListTag list) {
                    ListTag reversed = new ListTag();
                    for (int i = list.size() - 1; i >= 0; i--)
                        reversed.add(list.get(i).copy());
                    compound.put(name, reversed);
                } else reverse(child);
            }
        } else if (tag instanceof ListTag list) {
            for (Tag child : list) reverse(child);
        }
    }
}
