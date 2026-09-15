// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

final class ResourceVariantDecodingTest {
    @Test
    void restorationNeverDiscoversCapabilitiesOrAcceptsChangedIdentity() {
        ResourceLocation type = ResourceLocation.parse("example:resource");
        var key = new ResourceVariantKey(type, new byte[] {7});
        var directory = new ResourceAdapterDirectory(1);
        directory.register(
                new ResourceAdapterDirectory.Descriptor(type, "unit", 1),
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                (handler, provider) -> {
                    throw new AssertionError("Decoder discovered a native endpoint");
                },
                (stored, provider) -> EnergyVariant.INSTANCE);
        directory.freeze();
        assertTrue(directory.decode(key, REGISTRIES).isEmpty());
        var withoutDecoder = new ResourceAdapterDirectory(1);
        withoutDecoder.register(
                new ResourceAdapterDirectory.Descriptor(type, "unit", 1),
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                (handler, provider) -> {
                    throw new AssertionError("Opaque lookup discovered a native endpoint");
                });
        withoutDecoder.freeze();
        assertTrue(withoutDecoder.decode(key, REGISTRIES).isEmpty());
    }

    private static final HolderLookup.Provider REGISTRIES =
            HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(registry -> registry.asLookup()));

    @Test
    void storedNativeIdentitiesReconstructEveryComponentWithoutQuantities() {
        var directory = ResourceAdapterDirectory.nativeDefaults();
        ItemStack stack = new ItemStack(Items.IRON_INGOT, 43);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Stored iron"));
        stack.remove(DataComponents.MAX_STACK_SIZE);
        ItemVariant item = ItemVariant.from(stack, REGISTRIES);
        var decoded = (ItemVariant) directory.decode(item.key(), REGISTRIES).orElseThrow();
        assertEquals(item.key(), decoded.key());
        assertTrue(ItemStack.isSameItemSameComponents(stack, decoded.stack(1)));
        FluidStack fluid = new FluidStack(Fluids.WATER, 17000);
        fluid.set(DataComponents.CUSTOM_NAME, Component.literal("Stored water"));
        FluidVariant water = FluidVariant.from(fluid, REGISTRIES);
        var restored = (FluidVariant) directory.decode(water.key(), REGISTRIES).orElseThrow();
        assertEquals(water.key(), restored.key());
        assertTrue(FluidStack.isSameFluidSameComponents(fluid, restored.stack(1)));
        assertEquals(
                EnergyVariant.INSTANCE,
                directory.decode(EnergyVariant.INSTANCE.key(), REGISTRIES).orElseThrow());
    }

    @Test
    void missingAdaptersIdsComponentsAndMalformedKeysRemainOpaque() {
        var directory = ResourceAdapterDirectory.nativeDefaults();
        assertTrue(directory
                .decode(new ResourceVariantKey(ResourceLocation.parse("missing:resource"), new byte[] {1}), REGISTRIES)
                .isEmpty());
        assertTrue(directory
                .decode(new ResourceVariantKey(ResourceTypes.ENERGY, new byte[] {1}), REGISTRIES)
                .isEmpty());
        assertTrue(directory
                .decode(new ResourceVariantKey(ResourceTypes.ITEM, new byte[] {1}), REGISTRIES)
                .isEmpty());
        CompoundTag identity = new CompoundTag();
        identity.putString("id", "missing:item");
        identity.put("components", new CompoundTag());
        assertTrue(directory
                .decode(new ResourceVariantKey(ResourceTypes.ITEM, CanonicalResourceNbt.encode(identity)), REGISTRIES)
                .isEmpty());
        identity.putString("id", "minecraft:stone");
        identity.getCompound("components").putInt("missing:component", 7);
        ResourceVariantKey original = new ResourceVariantKey(ResourceTypes.ITEM, CanonicalResourceNbt.encode(identity));
        byte[] before = original.canonicalBytes();
        assertTrue(directory.decode(original, REGISTRIES).isEmpty());
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, original.canonicalBytes());
    }
}
