// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ItemFilterPresetTest {
    static final UUID ID = new UUID(1, 1);
    static final ResourceLocation STONE = ResourceLocation.parse("minecraft:stone");

    @Test
    void missingSelectionBlocksBeforeBlacklistInversionAndAbsentSelectionAllows() {
        assertTrue(ItemFilterPreset.allows(null, null, FilterMode.WHITELIST, STONE));
        assertFalse(ItemFilterPreset.allows(ID, null, FilterMode.BLACKLIST, STONE));
        ItemFilterPreset empty = new ItemFilterPreset(ID, new ManagedName("Empty"), 0, Set.of());
        assertFalse(ItemFilterPreset.allows(ID, empty, FilterMode.WHITELIST, STONE));
        assertTrue(ItemFilterPreset.allows(ID, empty, FilterMode.BLACKLIST, STONE));
    }

    @Test
    void exactIdsAreImmutableAndRevisionIsValidated() {
        Set<ResourceLocation> ids = new HashSet<>(Set.of(STONE));
        ItemFilterPreset preset = new ItemFilterPreset(ID, new ManagedName("Stone"), 0, ids);
        ids.clear();
        assertTrue(ItemFilterPreset.allows(ID, preset, FilterMode.WHITELIST, STONE));
        assertFalse(ItemFilterPreset.allows(ID, preset, FilterMode.BLACKLIST, STONE));
        assertFalse(
                ItemFilterPreset.allows(ID, preset, FilterMode.WHITELIST, ResourceLocation.parse("minecraft:dirt")));
        assertThrows(UnsupportedOperationException.class, () -> preset.itemIds().clear());
        assertThrows(
                IllegalArgumentException.class, () -> new ItemFilterPreset(ID, new ManagedName("Bad"), -1, Set.of()));
    }

    @Test
    void fullVariantsMatchOnlyTheirRegisteredItemIdWithoutComponentReconstruction() {
        var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE);
        stack.set(
                net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Named"));
        var variant = io.github.loongin.omniresonance.transfer.ItemVariant.from(
                stack, net.minecraft.core.RegistryAccess.EMPTY);
        assertTrue(new ItemFilterPreset(ID, new ManagedName("Stone"), 0, Set.of(STONE)).matches(variant));
        org.junit.jupiter.api.Assertions.assertEquals(STONE, variant.itemId());
    }

    @Test
    void hardBoundsAllowMoreThanGameplayQuotaButRejectNativeStringAndObjectOverflow() {
        Set<ResourceLocation> many = new HashSet<>();
        for (int index = 0; index < 65536; index++) many.add(ResourceLocation.parse("example:item_" + index));
        org.junit.jupiter.api.Assertions.assertEquals(
                65536,
                new ItemFilterPreset(ID, new ManagedName("Many"), 0, many)
                        .itemIds()
                        .size());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ItemFilterPreset(
                        ID, new ManagedName("Long"), 0, Set.of(ResourceLocation.parse("x:" + "a".repeat(65534)))));
        Set<ResourceLocation> huge = new HashSet<>();
        for (int index = 0; index < 257; index++) huge.add(ResourceLocation.parse("x:" + index + "a".repeat(65520)));
        assertThrows(IllegalArgumentException.class, () -> new ItemFilterPreset(ID, new ManagedName("Huge"), 0, huge));
    }
}
