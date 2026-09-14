// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

final class ItemVariantTest {
    private static final HolderLookup.Provider PROVIDER =
            HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(registry -> registry.asLookup()));

    @Test
    void quantityIsIndependentAndFullEffectiveComponentsDefineIdentity() {
        ItemStack a = new ItemStack(Items.DIAMOND_SWORD, 1);
        ItemStack b = a.copyWithCount(200);
        ItemVariant v = ItemVariant.from(a, PROVIDER);
        assertEquals(v.key(), ItemVariant.from(b, PROVIDER).key());
        b.set(DataComponents.CUSTOM_NAME, Component.literal("Different"));
        assertNotEquals(v.key(), ItemVariant.from(b, PROVIDER).key());
        b = a.copy();
        b.setDamageValue(5);
        assertNotEquals(v.key(), ItemVariant.from(b, PROVIDER).key());
        ItemStack copy = v.stack(200);
        assertEquals(200, copy.getCount());
        copy.setDamageValue(10);
        assertEquals(0, v.stack(1).getDamageValue());
        a.setDamageValue(20);
        assertEquals(0, v.stack(1).getDamageValue());
    }

    @Test
    void removedDefaultsArePreservedAndTransientComponentsAreRejected() {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        stack.remove(DataComponents.DAMAGE);
        assertTrue(ItemStack.isSameItemSameComponents(
                stack, ItemVariant.from(stack, PROVIDER).stack(1)));
        DataComponentType<String> transientType = DataComponentType.<String>builder()
                .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8)
                .build();
        stack.set(transientType, "not persistent");
        assertThrows(IllegalArgumentException.class, () -> ItemVariant.from(stack, PROVIDER));
        assertThrows(IllegalArgumentException.class, () -> ItemVariant.from(ItemStack.EMPTY, PROVIDER));
    }

    @Test
    void effectiveDefaultsIgnorePatchHistoryAndUnknownPersistentComponentsFail() {
        ItemStack original = new ItemStack(Items.DIAMOND_SWORD);
        ItemStack changed = original.copy();
        changed.setDamageValue(5);
        changed.setDamageValue(0);
        assertEquals(
                ItemVariant.from(original, PROVIDER).key(),
                ItemVariant.from(changed, PROVIDER).key());
        assertEquals(
                net.minecraft.resources.ResourceLocation.parse("minecraft:item"),
                ItemVariant.from(original, PROVIDER).key().typeId());
        DataComponentType<String> unregistered = DataComponentType.<String>builder()
                .persistent(com.mojang.serialization.Codec.STRING)
                .build();
        changed.set(unregistered, "cannot encode its registry identifier");
        assertThrows(IllegalArgumentException.class, () -> ItemVariant.from(changed, PROVIDER));
        assertEquals("cannot encode its registry identifier", changed.get(unregistered));
        assertThrows(
                IllegalArgumentException.class,
                () -> ItemVariant.from(original, PROVIDER).stack(0));
    }
}
