// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.chunkloading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ChunkLoadingAllocatorTest {
    private static UUID id(int n) {
        return new UUID(0, n);
    }

    private static ChunkLoadingAllocator.Chunk chunk(int x) {
        return new ChunkLoadingAllocator.Chunk(ResourceLocation.parse("minecraft:overworld"), x, 0);
    }

    private static void put(ChunkLoadingAllocator a, int node, int owner, int x) {
        a.put(new ChunkLoadingAllocator.Request(
                id(node), id(owner), chunk(x), ChunkLoadingAllocator.Eligibility.READY));
    }

    @Test
    void physicalChangesCoalesceWithoutKeepingNeverIssuedTicketsOrDuplicateOwnerAdds() {
        var a = new ChunkLoadingAllocator(new ChunkLoadingAllocator.Limits(true, 2, 2));
        put(a, 1, 10, 0);
        a.advance(1);
        a.remove(id(1));
        org.junit.jupiter.api.Assertions.assertNull(a.pollTransition());
        put(a, 2, 10, 0);
        put(a, 3, 20, 0);
        a.advance(10);
        assertEquals(new ChunkLoadingAllocator.Transition(chunk(0), true), a.pollTransition());
        org.junit.jupiter.api.Assertions.assertNull(a.pollTransition());
        a.remove(id(2));
        org.junit.jupiter.api.Assertions.assertNull(a.pollTransition());
        a.remove(id(3));
        assertEquals(new ChunkLoadingAllocator.Transition(chunk(0), false), a.pollTransition());
        org.junit.jupiter.api.Assertions.assertNull(a.pollTransition());
    }

    @Test
    void eligibilityChangesReleaseOnlyTheAffectedReferenceAndRetainRequests() {
        var a = new ChunkLoadingAllocator(new ChunkLoadingAllocator.Limits(true, 1, 1));
        put(a, 1, 10, 0);
        put(a, 2, 10, 0);
        a.advance(10);
        a.put(new ChunkLoadingAllocator.Request(
                id(1), id(10), chunk(0), ChunkLoadingAllocator.Eligibility.NODE_DISABLED));
        assertEquals(1, a.physicalCount());
        a.put(new ChunkLoadingAllocator.Request(
                id(2), id(10), chunk(0), ChunkLoadingAllocator.Eligibility.DOMAIN_UNAVAILABLE));
        assertEquals(0, a.physicalCount());
        assertEquals(2, a.requestCount());
        assertEquals(ChunkLoadingAllocator.Status.NODE_DISABLED, a.status(id(1)));
        assertEquals(ChunkLoadingAllocator.Status.DOMAIN_UNAVAILABLE, a.status(id(2)));
        put(a, 2, 10, 0);
        a.advance(1);
        assertEquals(1, a.physicalCount());
    }

    @Test
    void dimensionsRemainDistinctAndShrinkingServerQuotaKeepsStableLowerChunks() {
        var a = new ChunkLoadingAllocator(new ChunkLoadingAllocator.Limits(true, 2, 2));
        put(a, 1, 10, 0);
        var nether = new ChunkLoadingAllocator.Chunk(ResourceLocation.parse("minecraft:the_nether"), 0, 0);
        a.put(new ChunkLoadingAllocator.Request(id(2), id(10), nether, ChunkLoadingAllocator.Eligibility.READY));
        a.advance(10);
        assertEquals(2, a.ownerCount(id(10)));
        assertEquals(2, a.physicalCount());
        a.limits(new ChunkLoadingAllocator.Limits(true, 2, 1));
        assertEquals(1, a.physicalCount());
        assertEquals(ChunkLoadingAllocator.Status.ACTIVE, a.status(id(1)));
        assertEquals(ChunkLoadingAllocator.Status.SERVER_LIMIT, a.status(id(2)));
    }

    @Test
    void tenThousandRequestsStillRespectASingleGrantWorkUnitAndNeverPreemptLiveGrants() {
        var a = new ChunkLoadingAllocator(new ChunkLoadingAllocator.Limits(true, 100000, 1000000));
        for (int i = 0; i < 10000; i++) put(a, i + 1, 20000, i);
        assertEquals(1, a.advance(1));
        assertEquals(1, a.physicalCount());
        assertEquals(10000, a.requestCount());
        assertEquals(3, a.advance(3));
        assertEquals(4, a.physicalCount());
        a.limits(new ChunkLoadingAllocator.Limits(true, 4, 4));
        put(a, 10001, 20000, -1);
        a.advance(100);
        assertEquals(ChunkLoadingAllocator.Status.OWNER_LIMIT, a.status(id(10001)));
        assertEquals(ChunkLoadingAllocator.Status.ACTIVE, a.status(id(1)));
    }

    @Test
    void ownerAndPhysicalQuotasDeduplicateDifferentKindsOfReferences() {
        var a = new ChunkLoadingAllocator(new ChunkLoadingAllocator.Limits(true, 1, 1));
        put(a, 1, 10, 0);
        put(a, 2, 10, 0);
        put(a, 3, 20, 0);
        put(a, 4, 10, 1);
        a.advance(20);
        assertEquals(1, a.physicalCount());
        assertEquals(1, a.ownerCount(id(10)));
        assertEquals(1, a.ownerCount(id(20)));
        assertEquals(ChunkLoadingAllocator.Status.ACTIVE, a.status(id(1)));
        assertEquals(ChunkLoadingAllocator.Status.OWNER_LIMIT, a.status(id(4)));
        a.remove(id(1));
        a.remove(id(2));
        assertEquals(1, a.physicalCount());
        a.remove(id(3));
        assertEquals(0, a.physicalCount());
        a.advance(20);
        assertEquals(ChunkLoadingAllocator.Status.ACTIVE, a.status(id(4)));
    }

    @Test
    void oneOwnerCannotTakeAllSlotsBeforeTheNextOwnerAndExistingGrantsRemainStable() {
        var a = new ChunkLoadingAllocator(new ChunkLoadingAllocator.Limits(true, 25, 2));
        put(a, 1, 10, 5);
        put(a, 2, 10, 1);
        put(a, 3, 20, 8);
        assertEquals(1, a.advance(1));
        assertEquals(ChunkLoadingAllocator.Status.ACTIVE, a.status(id(2)));
        assertEquals(1, a.advance(1));
        assertEquals(ChunkLoadingAllocator.Status.ACTIVE, a.status(id(3)));
        a.advance(100);
        assertEquals(ChunkLoadingAllocator.Status.SERVER_LIMIT, a.status(id(1)));
        assertEquals(ChunkLoadingAllocator.Status.ACTIVE, a.status(id(2)));
    }

    @Test
    void disabledAndReducedLimitsKeepRequestsForLaterReadmission() {
        var a = new ChunkLoadingAllocator(new ChunkLoadingAllocator.Limits(true, -1, -1));
        put(a, 1, 10, 0);
        put(a, 2, 10, 1);
        put(a, 3, 20, 2);
        a.advance(30);
        a.limits(new ChunkLoadingAllocator.Limits(false, 25, 500));
        assertEquals(0, a.physicalCount());
        assertEquals(3, a.requestCount());
        assertEquals(ChunkLoadingAllocator.Status.SERVER_DISABLED, a.status(id(1)));
        a.limits(new ChunkLoadingAllocator.Limits(true, 1, 1));
        a.advance(30);
        assertEquals(1, a.physicalCount());
        a.limits(new ChunkLoadingAllocator.Limits(true, 0, 0));
        assertEquals(0, a.physicalCount());
        assertEquals(3, a.requestCount());
    }

    @Test
    void boundedScanningCanFindASharedChunkBehindAGloballyBlockedCandidate() {
        var a = new ChunkLoadingAllocator(new ChunkLoadingAllocator.Limits(true, 2, 1));
        put(a, 1, 10, 2);
        a.advance(1);
        put(a, 2, 20, 1);
        put(a, 3, 20, 2);
        for (int i = 0; i < 8; i++) a.advance(1);
        assertEquals(ChunkLoadingAllocator.Status.ACTIVE, a.status(id(3)));
        assertEquals(ChunkLoadingAllocator.Status.SERVER_LIMIT, a.status(id(2)));
        assertTrue(a.physicalCount() <= 1);
    }
}
