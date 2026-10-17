// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.ArrayList;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

/** Known native component ordering, independent of registries and world state. Only effect cure sets are
 * unordered. All other lists, unknown components and arbitrary custom NBT retain their exact identity. */
public final class PersistentComponentOrder {
    private static final byte[] CURES = "neoforge:cures".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private PersistentComponentOrder() {}

    static ResourceVariantKey normalize(ResourceVariantKey key) {
        if (!key.typeId().equals(ResourceTypes.ITEM) && !key.typeId().equals(ResourceTypes.FLUID)) return key;
        byte[] bytes = key.canonicalBytes();
        if (!containsCures(bytes)) return key;
        try {
            Tag tag = CanonicalResourceNbt.decode(bytes);
            if (!(tag instanceof CompoundTag root)
                    || !root.getAllKeys().equals(java.util.Set.of("id", "components"))
                    || !root.contains("id", Tag.TAG_STRING)
                    || !(root.get("components") instanceof CompoundTag components)) return key;
            return normalizeComponents(components)
                    ? new ResourceVariantKey(key.typeId(), CanonicalResourceNbt.encode(root))
                    : key;
        } catch (IllegalArgumentException malformed) {
            // Opaque data is never repaired, erased or interpreted as a different resource.
            return key;
        }
    }

    private static boolean containsCures(byte[] bytes) {
        outer:
        for (int i = 0; i <= bytes.length - CURES.length; i++) {
            for (int j = 0; j < CURES.length; j++) if (bytes[i + j] != CURES[j]) continue outer;
            return true;
        }
        return false;
    }

    /** Mutates only the caller-owned, bounded decoded component map; no authority, registry access or caching.
     * Returns whether any known set order changed. Duplicate/unknown members survive for strict codec validation. */
    public static boolean normalizeComponents(CompoundTag components) {
        boolean changed = false;
        for (String name : components.getAllKeys()) changed |= normalizeComponent(name, components.get(name));
        return changed;
    }

    /** Mutates a caller-owned bounded encoded component value, with the same rules as normalizeComponents. */
    public static boolean normalizeComponent(String name, Tag value) {
        return switch (name) {
            case "minecraft:food" -> food(value);
            case "minecraft:potion_contents" -> potion(value);
            case "minecraft:container" -> container(value);
            case "minecraft:bundle_contents", "minecraft:charged_projectiles" -> stacks(value);
            default -> false;
        };
    }

    private static boolean food(Tag value) {
        if (!(value instanceof CompoundTag food)) return false;
        boolean changed = stack(food.get("using_converts_to"));
        if (food.get("effects") instanceof ListTag effects) {
            for (Tag child : effects)
                if (child instanceof CompoundTag possible) changed |= effect(possible.get("effect"));
        }
        return changed;
    }

    private static boolean potion(Tag value) {
        boolean changed = false;
        if (value instanceof CompoundTag potion && potion.get("custom_effects") instanceof ListTag effects)
            for (Tag child : effects) changed |= effect(child);
        return changed;
    }

    private static boolean effect(Tag value) {
        if (!(value instanceof CompoundTag effect)) return false;
        boolean changed = effect(effect.get("hidden_effect"));
        if (effect.get("neoforge:cures") instanceof ListTag cures
                && cures.getElementType() == Tag.TAG_STRING
                && cures.size() > 1) {
            ArrayList<String> sorted = new ArrayList<>(cures.size());
            for (int i = 0; i < cures.size(); i++) sorted.add(cures.getString(i));
            sorted.sort(String::compareTo);
            for (int i = 0; i < cures.size(); i++) {
                if (!sorted.get(i).equals(cures.getString(i))) {
                    ListTag replacement = new ListTag();
                    for (String cure : sorted) replacement.add(net.minecraft.nbt.StringTag.valueOf(cure));
                    effect.put("neoforge:cures", replacement);
                    return true;
                }
            }
        }
        return changed;
    }

    private static boolean container(Tag value) {
        boolean changed = false;
        if (value instanceof ListTag slots)
            for (Tag child : slots) if (child instanceof CompoundTag slot) changed |= stack(slot.get("item"));
        return changed;
    }

    private static boolean stacks(Tag value) {
        boolean changed = false;
        if (value instanceof ListTag stacks) for (Tag child : stacks) changed |= stack(child);
        return changed;
    }

    private static boolean stack(Tag value) {
        return value instanceof CompoundTag stack
                && stack.get("components") instanceof CompoundTag components
                && normalizeComponents(components);
    }

    static CompoundTag encode(DataComponentMap components, HolderLookup.Provider provider) {
        CompoundTag encoded = (CompoundTag) DataComponentMap.CODEC
                .encodeStart(provider.createSerializationContext(NbtOps.INSTANCE), components)
                .getOrThrow();
        if (!hasNestedEffects(components)) return encoded;
        // Codec encoding may return cached tags. Never mutate them or native component values.
        encoded = encoded.copy();
        normalizeComponents(encoded);
        return encoded;
    }

    static boolean hasNestedEffects(DataComponentMap components) {
        return components.has(net.minecraft.core.component.DataComponents.FOOD)
                || components.has(net.minecraft.core.component.DataComponents.POTION_CONTENTS)
                || components.has(net.minecraft.core.component.DataComponents.CONTAINER)
                || components.has(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS)
                || components.has(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES);
    }

    static boolean needsPersistentComparison(DataComponentMap first, DataComponentMap second) {
        return first.get(net.minecraft.core.component.DataComponents.FOOD)
                        != second.get(net.minecraft.core.component.DataComponents.FOOD)
                || first.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS)
                        != second.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS)
                || first.get(net.minecraft.core.component.DataComponents.CONTAINER)
                        != second.get(net.minecraft.core.component.DataComponents.CONTAINER)
                || first.get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS)
                        != second.get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS)
                || first.get(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES)
                        != second.get(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES);
    }

    static boolean validRoundTrip(
            DataComponentMap first, DataComponentMap second, CompoundTag encoded, HolderLookup.Provider provider) {
        return sameComponents(first, second, provider)
                && (!hasNestedEffects(first) || encoded.equals(encode(second, provider)));
    }

    static boolean equivalentComponent(String name, Tag first, Tag second) {
        if (first.equals(second)) return true;
        Tag a = first.copy(), b = second.copy();
        normalizeComponent(name, a);
        normalizeComponent(name, b);
        return a.equals(b);
    }

    private static boolean sameNestedValues(Object first, Object second, HolderLookup.Provider provider) {
        if (first instanceof net.minecraft.world.food.FoodProperties a
                && second instanceof net.minecraft.world.food.FoodProperties b) {
            return sameNestedStack(
                    a.usingConvertsTo().orElse(net.minecraft.world.item.ItemStack.EMPTY),
                    b.usingConvertsTo().orElse(net.minecraft.world.item.ItemStack.EMPTY),
                    provider);
        }
        if (first instanceof net.minecraft.world.item.component.ItemContainerContents a
                && second instanceof net.minecraft.world.item.component.ItemContainerContents b) {
            if (a.getSlots() != b.getSlots()) return false;
            for (int i = 0; i < a.getSlots(); i++)
                if (!sameNestedStack(a.getStackInSlot(i), b.getStackInSlot(i), provider)) return false;
            return true;
        }
        if (first instanceof net.minecraft.world.item.component.BundleContents a
                && second instanceof net.minecraft.world.item.component.BundleContents b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++)
                if (!sameNestedStack(a.getItemUnsafe(i), b.getItemUnsafe(i), provider)) return false;
            return true;
        }
        if (first instanceof net.minecraft.world.item.component.ChargedProjectiles a
                && second instanceof net.minecraft.world.item.component.ChargedProjectiles b) {
            var left = a.getItems();
            var right = b.getItems();
            if (left.size() != right.size()) return false;
            for (int i = 0; i < left.size(); i++)
                if (!sameNestedStack(left.get(i), right.get(i), provider)) return false;
            return true;
        }
        return false;
    }

    private static boolean sameNestedStack(
            net.minecraft.world.item.ItemStack first,
            net.minecraft.world.item.ItemStack second,
            HolderLookup.Provider provider) {
        return first.getCount() == second.getCount() && ItemVariant.sameIdentity(first, second, provider);
    }

    static boolean sameComponents(DataComponentMap first, DataComponentMap second, HolderLookup.Provider provider) {
        if (!first.keySet().equals(second.keySet())) return false;
        for (TypedDataComponent<?> component : first) {
            Object other = second.get(component.type());
            if (Objects.equals(component.value(), other)) continue;
            String name = BuiltInRegistries.DATA_COMPONENT_TYPE
                    .getKey(component.type())
                    .toString();
            // NeoForge food effects hold Suppliers; containers inherit the nested stack equality limitation.
            if (!name.equals("minecraft:food")
                    && !name.equals("minecraft:container")
                    && !name.equals("minecraft:bundle_contents")
                    && !name.equals("minecraft:charged_projectiles")) return false;
            if (!sameNestedValues(component.value(), other, provider)) return false;
            var ops = provider.createSerializationContext(NbtOps.INSTANCE);
            if (!equivalentComponent(
                    name,
                    component.encodeValue(ops).getOrThrow(),
                    TypedDataComponent.createUnchecked(component.type(), other)
                            .encodeValue(ops)
                            .getOrThrow())) return false;
        }
        return true;
    }
}
