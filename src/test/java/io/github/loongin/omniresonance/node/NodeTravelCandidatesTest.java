// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class NodeTravelCandidatesTest {
    @Test
    void searchIsFixedUniqueAndHeightOrdered() {
        var positions = NodeTravelCandidates.around(new BlockPos(7, 80, 7));
        assertEquals(75, positions.size());
        assertEquals(75, new HashSet<>(positions).size());
        for (int i = 0; i < 75; i += 3) {
            assertEquals(80, positions.get(i).getY());
            assertEquals(81, positions.get(i + 1).getY());
            assertEquals(79, positions.get(i + 2).getY());
            assertTrue(Math.abs(positions.get(i).getX() - 7) <= 2
                    && Math.abs(positions.get(i).getZ() - 7) <= 2);
        }
    }
}
