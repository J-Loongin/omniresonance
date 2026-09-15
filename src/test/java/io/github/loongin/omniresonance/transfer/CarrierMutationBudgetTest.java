// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CarrierMutationBudgetTest {
    @Test
    void extraContainerQueriesAreIncludedInExactAndGreedyBounds() {
        assertEquals(10, ResourceTransferEngine.maximumGreedyCalls(1, 1));
        assertEquals(13, ResourceTransferEngine.maximumGreedyCalls(2, 2));
        assertEquals(8, ResourceTransferEngine.maximumGreedyCalls(2, 0));
        assertEquals(5, ResourceTransferEngine.maximumGreedyCalls(0, 2));
        assertThrows(IllegalArgumentException.class, () -> ResourceTransferEngine.maximumGreedyCalls(-1, 1));
    }
}
