// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.chunkloading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ChunkLoadingReservationsTest {
    private static UUID id(int i) {
        return new UUID(0, i);
    }

    private static ChunkLoadingAllocator.Chunk chunk(int x) {
        return new ChunkLoadingAllocator.Chunk(ResourceLocation.parse("minecraft:overworld"), x, 0);
    }

    @Test
    void lowerLimitsKeepReservationsAndOnlyZeroCostSharingRemainsAdmissible() {
        var r = new ChunkLoadingReservations();
        for (int i = 0; i < 18; i++) r.put(id(100), id(i + 1), id(200), chunk(i));
        var limits = new ChunkLoadingAllocator.Limits(true, 10, 10);
        assertEquals(18, r.ownerCount(id(200)));
        assertEquals(18, r.serverCount());
        assertEquals(ChunkLoadingReservations.Admission.OWNER_LIMIT, r.check(id(200), chunk(30), limits));
        assertEquals(ChunkLoadingReservations.Admission.ALLOWED, r.check(id(200), chunk(0), limits));
        assertEquals(ChunkLoadingReservations.Admission.SERVER_LIMIT, r.check(id(201), chunk(30), limits));
        r.retain(id(100), Set.of(id(1)));
        assertEquals(1, r.serverCount());
        assertEquals(ChunkLoadingReservations.Admission.ALLOWED, r.check(id(200), chunk(30), limits));
    }

    @Test
    void ownerAndPhysicalReferencesSurviveDisabledLimitsAndReleaseOnlyTheirOwnScope() {
        var r = new ChunkLoadingReservations();
        r.put(id(100), id(1), id(200), chunk(0));
        r.put(id(101), id(2), id(200), chunk(0));
        r.put(id(102), id(3), id(201), chunk(0));
        assertEquals(1, r.serverCount());
        assertEquals(1, r.ownerCount(id(200)));
        assertEquals(
                ChunkLoadingReservations.Admission.SERVER_DISABLED,
                r.check(id(200), chunk(0), new ChunkLoadingAllocator.Limits(false, 0, 0)));
        r.retain(id(100), Set.of());
        assertEquals(1, r.ownerCount(id(200)));
        r.retain(id(101), Set.of());
        assertEquals(0, r.ownerCount(id(200)));
        assertEquals(1, r.serverCount());
        r.retain(id(102), Set.of());
        assertEquals(0, r.serverCount());
    }
}
