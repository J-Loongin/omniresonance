// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import io.github.loongin.omniresonance.transfer.CanonicalResourceNbt;
import io.github.loongin.omniresonance.transfer.FluidVariant;
import io.github.loongin.omniresonance.transfer.ItemVariant;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.ResourceVariant;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Detached immutable filter input; contains no native stack, registry, world or provider references. */
public final class FilterResourceSample {
    private final ResourceLocation typeId;
    private final String resourceId;
    private final FilterComponentBytes full;
    private final Map<String, FilterComponentBytes> components;

    private FilterResourceSample(
            ResourceLocation typeId, String resourceId, byte[] full, Map<String, FilterComponentBytes> components) {
        this.typeId = typeId;
        this.resourceId = resourceId;
        this.full = new FilterComponentBytes(full);
        this.components = Collections.unmodifiableMap(components);
    }
    /** Editor convenience and explicitly admitted runtime preparation from a captured identity.
     * Bounded decode/canonicalization owns all arrays. Runtime callers first inspect preparationCost,
     * admit this operation under the soft CPU budget and recheck time afterward. Prepare once per
     * candidate, never once per rule. No native access, authority mutation or simulation occurs. */
    public static FilterResourceSample capture(ResourceVariant variant) {
        ResourceVariantKey key = Objects.requireNonNull(variant).key();
        if (key.typeId().equals(ResourceTypes.ENERGY))
            return new FilterResourceSample(key.typeId(), "", new byte[0], Map.of());
        if (!key.typeId().equals(ResourceTypes.ITEM) && !key.typeId().equals(ResourceTypes.FLUID))
            return new FilterResourceSample(key.typeId(), "", new byte[0], Map.of());
        CompoundTag identity = (CompoundTag) CanonicalResourceNbt.decode(key.canonicalBytes());
        CompoundTag values = identity.getCompound("components");
        Map<String, FilterComponentBytes> entries = new HashMap<>();
        for (String name : values.getAllKeys())
            entries.put(
                    name,
                    new FilterComponentBytes(CanonicalResourceNbt.encode(Objects.requireNonNull(values.get(name)))));
        return new FilterResourceSample(
                key.typeId(),
                ResourceLocation.parse(identity.getString("id")).toString(),
                CanonicalResourceNbt.encode(values),
                entries);
    }
    /** Size evidence rather than a pretend constant work token. Full preparation performs bounded NBT
     * decode and canonical encoding, O(B log B) time and O(B) memory (depth <=64, B<=262144).
     * The caller admits one such operation under a soft CPU budget, then rechecks elapsed CPU time. */
    public record PreparationCost(int maximumInputBytes, boolean decodesComponents) {}

    public static PreparationCost preparationCost(ResourceVariant variant, boolean components) {
        ResourceVariantKey key = Objects.requireNonNull(variant).key();
        boolean genericNativeKey = !(variant instanceof ItemVariant)
                && !(variant instanceof FluidVariant)
                && (key.typeId().equals(ResourceTypes.ITEM) || key.typeId().equals(ResourceTypes.FLUID));
        return new PreparationCost(key.encodedSizeBytes(), components || genericNativeKey);
    }
    /** Server-thread native fast path reads only the ID. Generic item/fluid keys require one admitted,
     * bounded canonical NBT decode (including component payload), but no component re-encoding.
     * The fallback owns the decoded value, retains only its ID, and never accesses native state. */
    public static FilterResourceSample idOnly(ResourceVariant variant) {
        Objects.requireNonNull(variant);
        ResourceVariantKey key = variant.key();
        String id;
        if (variant instanceof ItemVariant item) id = item.itemId().toString();
        else if (variant instanceof FluidVariant fluid) id = fluid.fluidId().toString();
        else if (key.typeId().equals(ResourceTypes.ITEM) || key.typeId().equals(ResourceTypes.FLUID)) {
            CompoundTag identity = (CompoundTag) CanonicalResourceNbt.decode(key.canonicalBytes());
            id = ResourceLocation.parse(identity.getString("id")).toString();
        } else id = "";
        return new FilterResourceSample(key.typeId(), id, new byte[0], Map.of());
    }

    public ResourceLocation typeId() {
        return typeId;
    }

    public String resourceId() {
        return resourceId;
    }

    public Set<String> componentKeys() {
        return components.keySet();
    }

    FilterComponentBytes fullBytes() {
        return full;
    }

    FilterComponentBytes componentBytes(String key) {
        return components.get(key);
    }
}
