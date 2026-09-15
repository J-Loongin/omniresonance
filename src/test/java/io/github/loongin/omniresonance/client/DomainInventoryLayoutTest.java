// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DomainInventoryLayoutTest {
    @Test
    void dedicatedWindowUsesNineColumnsAndSpendsHeightOnStorageRows() {
        var small = DomainInventoryLayout.window(427, 240);
        var large = DomainInventoryLayout.window(960, 540);
        assertEquals(9, new DomainInventoryLayout(small.content(), true).columns());
        assertEquals(9, new DomainInventoryLayout(large.content(), true).columns());
        assertTrue(small.window().width() < 250);
        assertTrue(new DomainInventoryLayout(large.content(), true).rows()
                > new DomainInventoryLayout(small.content(), true).rows());
    }

    @Test
    void gridAndAllPlayerSlotsStayAlignedAndHotbarGapIsNotClickable() {
        for (int[] size : new int[][] {{320, 240}, {427, 240}, {960, 540}}) {
            var body = TerminalLayout.calculate(size[0], size[1]).content();
            var layout = new DomainInventoryLayout(body, true);
            assertTrue(layout.columns() >= 9);
            assertTrue(layout.rows() >= 2);
            assertTrue(layout.gridRight() + 9 <= body.right());
            assertTrue(layout.gridBottom() < layout.statusY());
            assertEquals(layout.gridX(), layout.slotX(0));
            for (int slot = 0; slot < 36; slot++) {
                assertEquals(slot, layout.inventorySlot(layout.slotX(slot) + 1, layout.slotY(slot) + 1));
                assertTrue(layout.slotY(slot) + 18 <= body.bottom());
                assertFalse(layout.inGrid(layout.slotX(slot), layout.slotY(slot)));
            }
            assertEquals(-1, layout.inventorySlot(layout.slotX(0), layout.inventoryY() + 56));
        }
    }
}
