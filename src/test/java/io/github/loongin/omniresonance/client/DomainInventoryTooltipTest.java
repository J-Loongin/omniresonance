// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class DomainInventoryTooltipTest {
    @Test
    void quantitiesStayCompactEvenAtLongCapacity() {
        assertQuantity("9.22P", true, Long.MAX_VALUE);
        assertQuantity("9.22E", false, Long.MAX_VALUE);
        assertQuantity("999.999", true, 999999);
        assertQuantity("1K", true, 1000000);
        assertQuantity("0.001", true, 1);
        assertQuantity("0", false, 0);
        assertQuantity("999.99K", false, 999999);
    }

    private static void assertQuantity(String expected, boolean buckets, long amount) {
        var lines = DomainInventoryTooltip.lines("Resource", List.of(), false, false, buckets, false, amount, "unit");
        var content = (net.minecraft.network.chat.contents.TranslatableContents)
                lines.getLast().getContents();
        assertEquals(expected, content.getArgs()[0]);
        boolean approximate = amount == Long.MAX_VALUE || (!buckets && amount == 999999);
        assertEquals(
                "omniresonance.inventory." + (approximate ? "quantity_approximate" : "quantity"), content.getKey());
    }

    @Test
    void nativeDetailsKeepTheirStylesAndPrecedeStoredQuantity() {
        var warning = Component.literal("Radiation: 10.0 mSv/h").withStyle(net.minecraft.ChatFormatting.RED);
        var source = Component.literal("Mekanism")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY, net.minecraft.ChatFormatting.ITALIC);
        var lines =
                DomainInventoryTooltip.lines("Waste", List.of(warning, source), false, false, true, false, 2000, "B");
        assertEquals(4, lines.size());
        assertEquals(warning, lines.get(1));
        assertEquals(source, lines.get(2));
        var amount = (net.minecraft.network.chat.contents.TranslatableContents)
                lines.get(3).getContents();
        assertEquals("omniresonance.inventory.quantity", amount.getKey());
        assertEquals("2", amount.getArgs()[0]);
    }

    @Test
    void ordinaryItemsDoNotRepeatAnExactSlotCount() {
        var lines = DomainInventoryTooltip.lines(
                "Iron", List.of(Component.literal("Iron")), true, false, false, false, 681, "");
        assertEquals(1, lines.size());
        assertEquals("Iron", lines.getFirst().getString());
        assertEquals(
                1,
                DomainInventoryTooltip.lines("Iron", List.of(), true, false, false, false, 999, "")
                        .size());
        assertEquals(
                2,
                DomainInventoryTooltip.lines("Iron", List.of(), true, false, false, false, 1000, "")
                        .size());
    }

    @Test
    void nativeDescriptionsAndUnitOrDurabilityQuantitiesRemain() {
        assertEquals(
                3,
                DomainInventoryTooltip.lines(
                                "Sword",
                                List.of(Component.literal("Sword"), Component.literal("Enchanted")),
                                true,
                                true,
                                false,
                                false,
                                1,
                                "")
                        .size());
        assertEquals(
                2,
                DomainInventoryTooltip.lines("Water", List.of(), false, false, true, false, 1000, "B")
                        .size());
        assertEquals(
                2,
                DomainInventoryTooltip.lines("Energy", List.of(), false, false, false, false, 10, "FE")
                        .size());
        assertEquals(
                3,
                DomainInventoryTooltip.lines("Unknown", List.of(), false, false, false, true, 1, "")
                        .size());
    }
}
