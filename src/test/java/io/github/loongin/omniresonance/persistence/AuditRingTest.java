// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class AuditRingTest {
    static AuditEntry entry(long tick) {
        return new AuditEntry(
                ResourceLocation.parse("omniresonance:rename_node"), new UUID(1, 1), new UUID(2, 2), tick, "changed");
    }

    @Test
    void reducedCapacityOnlyEvictsOnAppendAndZeroPreservesHistory() {
        var ring = new AuditRing();
        ring.append(entry(1), 3);
        ring.append(entry(2), 3);
        ring.append(entry(3), 3);
        assertFalse(ring.append(entry(4), 0));
        assertEquals(3, ring.size());
        ring.append(entry(5), 2);
        assertEquals(List.of(entry(3), entry(5)), ring.snapshot());
        assertThrows(IllegalArgumentException.class, () -> ring.append(entry(6), 10001));
        assertEquals(2, ring.size());
    }

    @Test
    void nbtRoundTripAndMalformedInputNeverMutateTheOriginalRing() {
        var ring = new AuditRing();
        ring.append(entry(1), 10);
        var tag = AuditNbt.encode(ring.snapshot());
        assertEquals(ring.snapshot(), AuditNbt.decode(tag));
        tag.getCompound(0).putString("summary", "x".repeat(129));
        assertThrows(IllegalArgumentException.class, () -> AuditNbt.decode(tag));
        assertEquals(List.of(entry(1)), ring.snapshot());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuditEntry(ResourceLocation.parse("minecraft:stone"), new UUID(1, 1), new UUID(2, 2), 1, ""));
    }
}
