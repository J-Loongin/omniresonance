// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.DynamicOps;
import io.github.loongin.omniresonance.transfer.FluidVariant;
import io.github.loongin.omniresonance.transfer.ItemVariant;
import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

final class ResourceDisplayValueTest {
    @Test
    void eightySixNativeItemsReuseValidatedStacksWithFewerComponentCodecContexts() {
        var provider = new CountingProvider();
        var adapters = ResourceAdapterDirectory.nativeDefaults();
        var keys = new ArrayList<ResourceVariantKey>();
        var items = BuiltInRegistries.ITEM.stream()
                .filter(item -> item != Items.AIR
                        && BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("minecraft"))
                .sorted(Comparator.comparing(
                        item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                .limit(86)
                .toList();
        for (var item : items)
            keys.add(ItemVariant.from(new ItemStack(item), provider).key());
        provider.contexts = 0;
        var previous = new ArrayList<ItemStack>();
        for (var key : keys)
            previous.add(((ItemVariant) adapters.decode(key, provider).orElseThrow()).stack(1));
        int oldContexts = provider.contexts;
        provider.contexts = 0;
        for (int i = 0; i < keys.size(); i++) {
            var snapshot = (ItemStack) ResourceDisplayValue.decode(keys.get(i), adapters, provider);
            assertTrue(ItemStack.isSameItemSameComponents(previous.get(i), snapshot));
        }
        assertTrue(
                oldContexts - provider.contexts >= keys.size(),
                "Display preparation repeated the post-validation component decode");
        org.slf4j.LoggerFactory.getLogger(getClass())
                .info(
                        "Display preparation: items={}, previousCodecContexts={}, snapshotCodecContexts={}",
                        keys.size(),
                        oldContexts,
                        provider.contexts);
    }

    @Test
    void nativeFluidPreviewRetainsStrictIdentityAndMalformedValuesStayOpaque() {
        var provider = new CountingProvider();
        var adapters = ResourceAdapterDirectory.nativeDefaults();
        var original = new FluidStack(Fluids.WATER, 1000);
        var key = FluidVariant.from(original, provider).key();
        assertTrue(FluidStack.isSameFluidSameComponents(
                original, (FluidStack) ResourceDisplayValue.decode(key, adapters, provider)));
        assertNull(ResourceDisplayValue.decode(
                new ResourceVariantKey(ResourceTypes.ITEM, new byte[] {0}), adapters, provider));
        assertNull(ResourceDisplayValue.decode(
                new ResourceVariantKey(ResourceTypes.FLUID, new byte[] {0}), adapters, provider));
    }

    private static final class CountingProvider implements HolderLookup.Provider {
        private final HolderLookup.Provider delegate = net.minecraft.data.registries.VanillaRegistries.createLookup();
        private int contexts;

        public Stream<ResourceKey<? extends Registry<?>>> listRegistries() {
            return delegate.listRegistries();
        }

        public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
            return delegate.lookup(key);
        }

        public <V> RegistryOps<V> createSerializationContext(DynamicOps<V> ops) {
            contexts++;
            return HolderLookup.Provider.super.createSerializationContext(ops);
        }
    }
}
