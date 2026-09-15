// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DomainFluidDisplayTest {
    @Test
    void tooltipSearchUsesTheDisplayedBucketQuantity() {
        var doc = new DomainInventoryQuery.Document(
                "water",
                "minecraft",
                "minecraft:water",
                "minecraft:fluid",
                java.util.List.of(),
                () -> "",
                "B",
                () -> 250);
        org.junit.jupiter.api.Assertions.assertTrue(
                DomainInventoryQuery.parse("$0.25").matches(doc));
        org.junit.jupiter.api.Assertions.assertFalse(
                DomainInventoryQuery.parse("$250").matches(doc));
    }

    @Test
    void bucketUnitsRetainSubBucketPrecisionAndLongBoundaries() {
        assertEquals("1", DomainFluidDisplay.exactBuckets(1000));
        assertEquals("0.001", DomainFluidDisplay.exactBuckets(1));
        assertEquals("0.25", DomainFluidDisplay.exactBuckets(250));
        assertEquals("1.001", DomainFluidDisplay.exactBuckets(1001));
        assertEquals("9223372036854775.807", DomainFluidDisplay.exactBuckets(Long.MAX_VALUE));
        assertEquals("1B", DomainFluidDisplay.compact(1000));
        assertEquals("0.25B", DomainFluidDisplay.compact(250));
        assertEquals("1KB", DomainFluidDisplay.compact(1000000));
    }
}
