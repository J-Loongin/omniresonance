// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ResourceTypesTest {
    @Test
    void builtInIdsAndExactBatchSamplesAreStablePureValues() {
        assertEquals("minecraft:item", ResourceTypes.ITEM.toString());
        assertEquals("minecraft:fluid", ResourceTypes.FLUID.toString());
        assertEquals("neoforge:energy", ResourceTypes.ENERGY.toString());
        assertEquals(64, ResourceTypes.defaultExactBatchSize(ResourceTypes.ITEM));
        assertEquals(1000, ResourceTypes.defaultExactBatchSize(ResourceTypes.FLUID));
        assertEquals(10000, ResourceTypes.defaultExactBatchSize(ResourceTypes.ENERGY));
    }
}
