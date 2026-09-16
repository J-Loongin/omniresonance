// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DomainInventoryTooltipTest {
    @Test
    void ordinaryItemsDoNotRepeatAnExactSlotCount() {
        var lines = DomainInventoryTooltip.lines("Iron", List.of("Iron"), true, false, false, false, 681, "");
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
                DomainInventoryTooltip.lines("Sword", List.of("Sword", "Enchanted"), true, true, false, false, 1, "")
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
