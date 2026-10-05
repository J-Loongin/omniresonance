// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/** Internal immutable fluid identity. Registry use and reconstruction remain on the calling server thread. */
public final class FluidVariant implements ResourceVariant {
    private static final ResourceLocation TYPE_ID = ResourceTypes.FLUID;
    private final ResourceVariantKey key;
    private final HolderLookup.Provider provider;
    private final Fluid fluid;

    private FluidVariant(ResourceVariantKey key, HolderLookup.Provider provider, Fluid fluid) {
        this.key = key;
        this.provider = provider;
        this.fluid = fluid;
    }

    /** Restores one trusted stored key on the server thread, without mutation or native calls. Unknown IDs,
     * malformed structure, or any lossy component decoding reject; callers must retain the opaque original key. */
    public static FluidVariant restore(ResourceVariantKey key, HolderLookup.Provider provider) {
        return restoreValue(key, provider).variant();
    }

    /**
     * Returns an independently reconstructed one-unit stack after the same strict identity checks as restore.
     * Call on the registry-owning game thread (including client presentation); no world access, simulation,
     * mutation of authority or caching occurs. The caller owns the returned stack. Malformed or lossy keys throw.
     */
    public static FluidStack restoreStack(ResourceVariantKey key, HolderLookup.Provider provider) {
        return restoreValue(key, provider).stack();
    }

    private record Restored(FluidVariant variant, FluidStack stack) {}

    private static Restored restoreValue(ResourceVariantKey key, HolderLookup.Provider provider) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(provider);
        if (!key.typeId().equals(TYPE_ID)) throw new IllegalArgumentException("Wrong resource type");
        var decoded = CanonicalResourceNbt.decode(key.canonicalBytes());
        if (!(decoded instanceof CompoundTag identity)
                || !identity.getAllKeys().equals(java.util.Set.of("id", "components"))
                || !identity.contains("id", net.minecraft.nbt.Tag.TAG_STRING)
                || !identity.contains("components", net.minecraft.nbt.Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Invalid stored fluid identity");
        ResourceLocation id = ResourceLocation.parse(identity.getString("id"));
        if (!id.toString().equals(identity.getString("id")) || !BuiltInRegistries.FLUID.containsKey(id))
            throw new IllegalArgumentException("Missing stored fluid");
        FluidVariant restored = new FluidVariant(key, provider, BuiltInRegistries.FLUID.get(id));
        FluidStack stack = restored.stack(1, identity);
        FluidVariant roundTrip = from(stack, provider);
        if (!roundTrip.key().equals(key)) throw new IllegalArgumentException("Stored fluid identity cannot round trip");
        return new Restored(restored, stack);
    }

    /** Captures full effective components without amount or patch history; rejects lossy codecs before mutation. */
    public static FluidVariant from(FluidStack stack, HolderLookup.Provider provider) {
        Objects.requireNonNull(stack);
        Objects.requireNonNull(provider);
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("Empty fluid variant");
        }
        Fluid fluid = stack.getFluid();
        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        if (fluidId == null || BuiltInRegistries.FLUID.get(fluidId) != fluid) {
            throw new IllegalArgumentException("Unregistered fluid variant");
        }
        for (TypedDataComponent<?> component : stack.getComponents()) {
            if (component.type().isTransient()) {
                throw new IllegalArgumentException("Transient fluid component");
            }
        }
        try {
            CompoundTag identity = new CompoundTag();
            identity.putString("id", fluidId.toString());
            identity.put(
                    "components",
                    DataComponentMap.CODEC
                            .encodeStart(provider.createSerializationContext(NbtOps.INSTANCE), stack.getComponents())
                            .getOrThrow());
            FluidVariant result = new FluidVariant(
                    new ResourceVariantKey(TYPE_ID, CanonicalResourceNbt.encode(identity)), provider, fluid);
            if (!FluidStack.isSameFluidSameComponents(stack, result.stack(1))) {
                throw new IllegalArgumentException("Fluid component codec loses identity");
            }
            return result;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Fluid variant cannot be persisted", ex);
        }
    }

    /** Returns the registered immutable fluid ID on the calling server thread without component decoding or mutation. */
    public ResourceLocation fluidId() {
        return Objects.requireNonNull(BuiltInRegistries.FLUID.getKey(fluid));
    }

    @Override
    public ResourceVariantKey key() {
        return key;
    }

    /** Decodes fresh owned components on each call. Positive int quantities are independent of the key. */
    public FluidStack stack(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Fluid amount must be positive");
        }
        CompoundTag identity = (CompoundTag) CanonicalResourceNbt.decode(key.canonicalBytes());
        return stack(amount, identity);
    }

    private FluidStack stack(int amount, CompoundTag identity) {
        DataComponentMap components = DataComponentMap.CODEC
                .parse(
                        provider.createSerializationContext(NbtOps.INSTANCE),
                        Objects.requireNonNull(identity.get("components")))
                .getOrThrow();
        DataComponentPatch.Builder patch = DataComponentPatch.builder();
        for (TypedDataComponent<?> component : components) {
            patch.set(component);
        }
        return new FluidStack(BuiltInRegistries.FLUID.wrapAsHolder(fluid), amount, patch.build());
    }
}
