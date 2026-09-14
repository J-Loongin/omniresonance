// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

final class FluidVariantTest {
    private static final HolderLookup.Provider PROVIDER =
            HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(registry -> registry.asLookup()));

    @Test
    void fullComponentsAndFluidDefineIdentityIndependentlyOfQuantity() {
        FluidStack sample = new FluidStack(Fluids.WATER, 1);
        sample.set(DataComponents.CUSTOM_NAME, Component.literal("Water"));
        FluidVariant variant = FluidVariant.from(sample, PROVIDER);
        assertEquals(ResourceTypes.FLUID, variant.key().typeId());
        assertEquals(BuiltInRegistries.FLUID.getKey(Fluids.WATER), variant.fluidId());
        assertEquals(
                variant.key(),
                FluidVariant.from(sample.copyWithAmount(Integer.MAX_VALUE), PROVIDER)
                        .key());
        assertNotEquals(
                variant.key(),
                FluidVariant.from(new FluidStack(Fluids.LAVA, 1), PROVIDER).key());
        sample.set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));
        assertNotEquals(variant.key(), FluidVariant.from(sample, PROVIDER).key());
        FluidStack returned = variant.stack(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, returned.getAmount());
        returned.set(DataComponents.CUSTOM_NAME, Component.literal("Returned change"));
        assertEquals(Component.literal("Water"), variant.stack(1).get(DataComponents.CUSTOM_NAME));
        assertThrows(IllegalArgumentException.class, () -> variant.stack(0));
    }

    @Test
    void canonicalComponentsIgnoreInsertionAndPatchHistory() {
        CompoundTag a = new CompoundTag();
        a.putInt("z", 2);
        a.putString("a", "one");
        CompoundTag b = new CompoundTag();
        b.putString("a", "one");
        b.putInt("z", 2);
        FluidStack first = new FluidStack(Fluids.WATER, 1);
        first.set(DataComponents.CUSTOM_DATA, CustomData.of(a));
        FluidStack second = new FluidStack(Fluids.WATER, 1000);
        second.set(DataComponents.CUSTOM_NAME, Component.literal("Temporary"));
        second.remove(DataComponents.CUSTOM_NAME);
        second.set(DataComponents.CUSTOM_DATA, CustomData.of(b));
        assertEquals(
                FluidVariant.from(first, PROVIDER).key(),
                FluidVariant.from(second, PROVIDER).key());
        assertTrue(FluidStack.isSameFluidSameComponents(
                first, FluidVariant.from(first, PROVIDER).stack(1)));
    }

    @Test
    void rejectsEmptyTransientAndUnregisteredComponents() {
        assertThrows(IllegalArgumentException.class, () -> FluidVariant.from(FluidStack.EMPTY, PROVIDER));
        DataComponentType<String> transientType = DataComponentType.<String>builder()
                .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                .build();
        FluidStack sample = new FluidStack(Fluids.WATER, 1);
        sample.set(transientType, "temporary");
        assertThrows(IllegalArgumentException.class, () -> FluidVariant.from(sample, PROVIDER));
        sample.remove(transientType);
        DataComponentType<String> unknown =
                DataComponentType.<String>builder().persistent(Codec.STRING).build();
        sample.set(unknown, "unknown");
        assertThrows(IllegalArgumentException.class, () -> FluidVariant.from(sample, PROVIDER));
        assertEquals("unknown", sample.get(unknown));
    }

    @Test
    void rejectsLossyRegisteredComponentRoundTrips() {
        FluidStack sample = new FluidStack(Fluids.WATER, 1);
        sample.set(DataComponents.CUSTOM_NAME, Component.translatable("test.argument", Component.literal("text")));
        RegistryOps<Tag> ops = PROVIDER.createSerializationContext(NbtOps.INSTANCE);
        DataComponentMap decoded = DataComponentMap.CODEC
                .parse(
                        ops,
                        DataComponentMap.CODEC
                                .encodeStart(ops, sample.getComponents())
                                .getOrThrow())
                .getOrThrow();
        // The native text codec collapses an unstyled component argument into a string.
        assertNotEquals(sample.getComponents(), decoded);
        assertThrows(IllegalArgumentException.class, () -> FluidVariant.from(sample, PROVIDER));
    }

    @Test
    void energyHasOneEmptyKeyAndCandidatesUsePositiveLongQuantities() {
        ResourceVariant energy = EnergyVariant.INSTANCE;
        assertEquals(ResourceTypes.ENERGY, energy.key().typeId());
        assertEquals(0, energy.key().canonicalBytes().length);
        assertEquals(Long.MAX_VALUE, new ResourceAmount(energy, Long.MAX_VALUE).quantity());
        assertThrows(IllegalArgumentException.class, () -> new ResourceAmount(energy, 0));
        assertThrows(IllegalArgumentException.class, () -> new ResourceAmount(energy, -1));
        assertThrows(NullPointerException.class, () -> new ResourceAmount(null, 1));
    }
}
